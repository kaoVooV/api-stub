/*
 *    Copyright 2016-2018 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.kazuki43zoo.apistub.api.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.kazuki43zoo.apistub.api.ApiStubProperties;
import com.kazuki43zoo.apistub.api.util.Lazy;
import com.kazuki43zoo.apistub.api.key.DataKeySupport;
import com.kazuki43zoo.apistub.domain.model.Api;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.StreamUtils;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

public class ApiEvidence {

  private static final DateTimeFormatter DIR_NAME_DATE_TIME_FORMAT =
      DateTimeFormatter.ofPattern("uuuuMMddHHmmssSSS");

  private static ObjectMapper objectMapperForLog =
      Jackson2ObjectMapperBuilder.json().dateFormat(StdDateFormat.instance).indentOutput(false).build();

  private static ObjectMapper objectMapperForFile =
      Jackson2ObjectMapperBuilder.json().dateFormat(StdDateFormat.instance).indentOutput(true).build();

  private final OffsetDateTime dateTime;
  private final Path dir;
  private final Logger logger;
  private final String contentExtension;
  private final ApiStubProperties properties;
  private final String dataKey;
  private final String correlationId;

  ApiEvidence(ApiStubProperties properties, DataKeySupport dataKeySupport, String method, String path, String dataKey, String correlationId, String contentExtension, Api api) {
    Optional<String> nullableDataKey = Optional.ofNullable(dataKey);
    this.dataKey = nullableDataKey.orElse("-");
    this.dateTime = OffsetDateTime.now();
    this.dir = Paths.get(properties.getEvidence().getDir(), path,
        dataKeySupport.isPathVariableDataKey(api) ? "" : nullableDataKey.orElse(""), method,
        dateTime.format(DIR_NAME_DATE_TIME_FORMAT) + "_" + correlationId);
    String loggerName = (api != null) ? (api.getMethod() + " " + api.getPath()) : (method + " " + path);
    this.logger = LoggerFactory.getLogger(loggerName);
    this.contentExtension = contentExtension;
    this.properties = properties;
    this.correlationId = correlationId;
  }

  public void start() {
    info("Start. key={}", () -> array(dataKey));
    if (properties.getEvidence().isDisabledRequest() && properties.getEvidence().isDisabledUpload()) {
      return;
    }
    if (!dir.toFile().exists() && !this.dir.toFile().mkdirs()) {
      error("Evidence Directory cannot create. dir = {}", dir.toAbsolutePath().toString());
    }
    info("Evidence Dir : {}", () -> array(dir.toAbsolutePath().toString()));
  }

  public void request(HttpServletRequest request, RequestEntity<byte[]> requestEntity) throws IOException, ServletException {
    final Supplier<EvidenceRequest> evidenceRequest = Lazy.of(
        () -> new EvidenceRequest(dateTime, request.getRequestURI(), request.getMethod(), request.getQueryString(),
            request.getParameterMap(), requestEntity.getHeaders()));
    info("Request      : {}", () -> array(toJson(evidenceRequest.get())));
    if (!properties.getEvidence().isDisabledRequest()) {
      try (OutputStream out = new BufferedOutputStream
          (new FileOutputStream(new File(dir.toFile(), "request.json")))) {
        objectMapperForFile.writeValue(out, evidenceRequest.get());
      }
    }

    if (requestEntity.getBody() != null) {
      info("Request body : {}", () -> array(new String(requestEntity.getBody(), getRequestCharset(requestEntity))));
      if (!properties.getEvidence().isDisabledRequest()) {
        try (OutputStream out = new BufferedOutputStream(
            new FileOutputStream(new File(dir.toFile(), "body." + contentExtension)))) {
          StreamUtils.copy(requestEntity.getBody(), out);
        }
      }
    } else {
      info("Request body : empty");
    }

    if (request instanceof MultipartHttpServletRequest) {
      int index = 1;
      for (Part part : request.getParts()) {
        String fileName = Paths.get(part.getSubmittedFileName()).getFileName().toString();
        String saveFileName = String.format("uploadFile_%02d_%s", index, fileName);
        File saveFile = new File(dir.toFile(), saveFileName);
        UploadFile uploadFile = new UploadFile(part, saveFileName);
        info("Upload file  : {}", () -> array(toJson(uploadFile)));
        if (!properties.getEvidence().isDisabledUpload()) {
          try (InputStream in = part.getInputStream();
               OutputStream out = new BufferedOutputStream(new FileOutputStream(saveFile))) {
            FileCopyUtils.copy(in, out);
          }
        }
        index++;
      }
    }
  }

  public void response(ResponseEntity<?> responseEntity) {
    info("Response     : {}", () -> array(toJson(new EvidenceResponse(responseEntity.getStatusCode(), responseEntity.getHeaders()))));
  }

  public static Object[] array(Object... args) {
    return args;
  }

  public void end() {
    info("End. key={}", () -> array(dataKey));
  }

  public void error(String format, Object... args) {
    logger.error(format, args);
  }

  public void error(String format, Throwable t) {
    logger.error(format, t);
  }

  public void warn(String msg) {
    logger.warn(msg);
  }

  public void warn(String format, Supplier<Object[]> argsSupplier) {
    if (logger.isWarnEnabled()) {
      logger.warn(format, argsSupplier.get());
    }
  }

  public void info(String msg) {
    logger.info(msg);
  }

  public void info(String format, Supplier<Object[]> argsSupplier) {
    if (logger.isInfoEnabled()) {
      logger.info(format, argsSupplier.get());
    }
  }

  public String getCorrelationId() {
    return correlationId;
  }

  private Charset getRequestCharset(RequestEntity<byte[]> requestEntity) {
    return Optional.ofNullable(requestEntity.getHeaders().getContentType())
        .map(MediaType::getCharset)
        .orElse(StandardCharsets.UTF_8);
  }

  private String toJson(Object object) {
    try {
      return objectMapperForLog.writeValueAsString(object);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static class UploadFile implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String saveFileName;
    private final long size;
    private final HttpHeaders headers;

    private UploadFile(Part part, String saveFileName) {
      this.saveFileName = saveFileName;
      this.size = part.getSize();
      HttpHeaders headers = new HttpHeaders();
      part.getHeaderNames().forEach(name -> headers.put(name, new ArrayList<>(part.getHeaders(name))));
      this.headers = HttpHeaders.readOnlyHttpHeaders(headers);
    }

    @SuppressWarnings("unused")
    public String getSaveFileName() {
      return saveFileName;
    }

    @SuppressWarnings("unused")
    public long getSize() {
      return size;
    }

    @SuppressWarnings("unused")
    public HttpHeaders getHeaders() {
      return headers;
    }
  }

  private static class EvidenceRequest {
    private final OffsetDateTime dateTime;
    private final String path;
    private final String method;
    private final String query;
    private final Map<String, String[]> parameters;
    private final HttpHeaders headers;

    private EvidenceRequest(OffsetDateTime dateTime, String path, String method, String query, Map<String, String[]> parameters, HttpHeaders headers) {
      this.dateTime = dateTime;
      this.path = path;
      this.method = method;
      this.query = query;
      this.parameters = parameters;
      this.headers = headers;
    }

    @SuppressWarnings("unused")
    public OffsetDateTime getDateTime() {
      return dateTime;
    }

    @SuppressWarnings("unused")
    public String getPath() {
      return path;
    }

    @SuppressWarnings("unused")
    public String getMethod() {
      return method;
    }

    @SuppressWarnings("unused")
    public String getQuery() {
      return query;
    }

    @SuppressWarnings("unused")
    public Map<String, String[]> getParameters() {
      return parameters;
    }

    @SuppressWarnings("unused")
    public HttpHeaders getHeaders() {
      return headers;
    }
  }

  private static class EvidenceResponse {
    private final HttpStatus httpStatus;
    private final HttpHeaders headers;

    private EvidenceResponse(HttpStatus httpStatus, HttpHeaders headers) {
      this.httpStatus = httpStatus;
      this.headers = headers;
    }

    @SuppressWarnings("unused")
    public HttpStatus getHttpStatus() {
      return httpStatus;
    }

    @SuppressWarnings("unused")
    public HttpHeaders getHeaders() {
      return headers;
    }
  }

}
