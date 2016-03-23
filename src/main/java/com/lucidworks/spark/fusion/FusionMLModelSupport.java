package com.lucidworks.spark.fusion;

import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.io.FileUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.EntityBuilder;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.message.BasicNameValuePair;
import org.apache.log4j.Logger;
import org.apache.spark.SparkContext;
import org.apache.spark.ml.util.MLWritable;
import org.apache.spark.mllib.util.Saveable;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public class FusionMLModelSupport {

  public static Logger log = Logger.getLogger(FusionMLModelSupport.class);

  public static void saveModelInFusion(String fusionHostAndPort,
                                       String fusionUser,
                                       String fusionPassword,
                                       String fusionRealm,
                                       SparkContext sc,
                                       String modelId,
                                       Object model,
                                       Map<String,String> metadata)
          throws Exception
  {
    // save model to local directory
    String modelType = metadata.get("modelType");
    File modelDir = getModelDir(modelId);
    if (model instanceof Saveable) {
      ((Saveable)model).save(sc, modelDir.getAbsolutePath());
      if (modelType == null) {
        modelType = "spark-mllib";
        metadata.put("modelType", modelType);
      }
    } else if (model instanceof MLWritable) {
      ((MLWritable)model).write().overwrite().save(modelDir.getAbsolutePath());
      if (modelType == null) {
        modelType = "spark-ml";
        metadata.put("modelType", modelType);
      }
    } else {
      throw new IllegalArgumentException("Provided ML model of type "+model.getClass().getName()+
              " does not implement "+Saveable.class.getName()+" or "+MLWritable.class.getName()+"!");
    }

    Map<String,Object> modelJson = new LinkedHashMap<>();
    modelJson.put("id", modelId);
    modelJson.put("modelType", modelType);
    modelJson.put("modelClassName", model.getClass().getName());
    metadata.remove("modelClassName");

    String featureFields = metadata.get("featureFields");
    if (featureFields != null) {
      modelJson.put("featureFields", Arrays.asList(featureFields.split(",")));
      metadata.remove("featureFields");
    }

    ObjectMapper om = new ObjectMapper();
    if ("spark-mllib".equals(modelType)) {
      // list of steps to vectorize data for this model
      List<Map<String,Object>> vectorizerSteps = new ArrayList<>();

      // save the Lucene Analyzer JSON file to local directory if provided
      String analyzerJson = metadata.get("analyzerJson");
      Map analyzerJsonMap = om.readValue(analyzerJson, Map.class);
      Map<String,Object> luceneAnalyzer = new HashMap<>();
      luceneAnalyzer.put("lucene-analyzer", analyzerJsonMap);
      vectorizerSteps.add(luceneAnalyzer);
      metadata.remove("analyzerJson");

      Map<String,Object> hashingTFMap = new HashMap<>();
      hashingTFMap.put("numFeatures", metadata.get("numFeatures"));
      Map<String,Object> hashingTF = new HashMap<>();
      hashingTF.put("hashingTF", hashingTFMap);
      vectorizerSteps.add(hashingTF);
      metadata.remove("numFeatures");

      modelJson.put("vectorizer", vectorizerSteps);
    }

    File modelJsonFile = new File(modelDir, modelType+".json");
    OutputStreamWriter osw = null;
    try {
      osw = new OutputStreamWriter(new FileOutputStream(modelJsonFile), StandardCharsets.UTF_8);
      om.writeValue(osw, modelJson);
    } finally {
      if (osw != null) {
        try {
          osw.flush();
        } catch (IOException ignoreMe) {}
        try {
          osw.close();
        } catch (IOException ignoreMe) {}
      }
    }
    metadata.put("modelSpec", modelJsonFile.getName());

    // zip local directory for loading into Fusion
    File zipFile = new File(modelId+".zip");
    if (zipFile.isFile()) {
      zipFile.delete();
    }

    addFilesToZip(modelDir, zipFile);

    // convert metadata into query string parameters for the PUT request to Fusion
    List<NameValuePair> pairs = new ArrayList<>();
    for (Map.Entry<String,String> entry : metadata.entrySet()) {
      pairs.add(new BasicNameValuePair(entry.getKey(), URLEncoder.encode(entry.getValue(), "UTF-8")));
    }

    String[] pair = fusionHostAndPort.split(":");
    String fusionHost = fusionHostAndPort;
    int fusionPort = 8764;
    if (pair.length == 2) {
      fusionHost = pair[0];
      fusionPort = Integer.parseInt(pair[1]);
    }

    URIBuilder builder = new URIBuilder();
    builder.setScheme("http").setHost(fusionHost).setPort(fusionPort).setPath("/api/apollo/blobs/"+modelId)
            .setParameters(pairs);
    HttpPut putRequest = new HttpPut(builder.build());
    putRequest.setHeader("Content-Type", "application/zip");

    EntityBuilder entityBuilder = EntityBuilder.create();
    entityBuilder.setContentType(ContentType.create("application/zip"));
    entityBuilder.setFile(zipFile);
    putRequest.setEntity(entityBuilder.build());

    // load the zip file with metadata into Fusion
    FusionPipelineClient fusionClient =
            new FusionPipelineClient(putRequest.getRequestLine().getUri(), fusionUser, fusionPassword, fusionRealm);
    fusionClient.sendRequestToFusion(putRequest);
  }

  protected static File getModelDir(String modelId) {
    File modelDir = new File(modelId);
    if (modelDir.isDirectory()) {
      // bak up existing
      SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmss");
      modelDir.renameTo(new File(modelId+"-bak-"+sdf.format(new Date())));
    }

    if (!modelDir.isDirectory()) {
      modelDir.mkdirs();
    }

    return modelDir;
  }

  protected static void addFilesToZip(File source, File destination) throws IOException, ArchiveException {
    OutputStream archiveStream = new FileOutputStream(destination);
    ArchiveOutputStream archive = new ArchiveStreamFactory().createArchiveOutputStream(ArchiveStreamFactory.ZIP, archiveStream);

    Collection<File> fileList = FileUtils.listFiles(source, null, true);

    for (File file : fileList) {
      String entryName = getEntryName(source, file);
      ZipArchiveEntry entry = new ZipArchiveEntry(entryName);
      archive.putArchiveEntry(entry);

      BufferedInputStream input = new BufferedInputStream(new FileInputStream(file));

      IOUtils.copy(input, archive);
      input.close();
      archive.closeArchiveEntry();
    }

    archive.finish();
    archiveStream.close();
  }

  protected static String getEntryName(File source, File file) throws IOException {
    int index = source.getAbsolutePath().length() + 1;
    String path = file.getCanonicalPath();

    return path.substring(index);
  }
}