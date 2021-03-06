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
package com.kazuki43zoo.apistub.ui.api;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.kazuki43zoo.apistub.domain.model.Api;
import com.kazuki43zoo.apistub.domain.model.KeyGeneratingStrategy;
import com.kazuki43zoo.apistub.domain.service.ApiService;
import com.kazuki43zoo.apistub.ui.DownloadSupport;
import com.kazuki43zoo.apistub.ui.ImportSupport;
import com.kazuki43zoo.apistub.ui.JsonSupport;
import com.kazuki43zoo.apistub.ui.PaginationSupport;
import com.kazuki43zoo.apistub.ui.component.message.ErrorMessage;
import com.kazuki43zoo.apistub.ui.component.message.InfoMessage;
import com.kazuki43zoo.apistub.ui.component.message.MessageCode;
import com.kazuki43zoo.apistub.ui.component.message.SuccessMessage;
import com.kazuki43zoo.apistub.ui.component.pagination.Pagination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.CookieGenerator;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RequestMapping("/manager/apis")
@Controller
@SessionAttributes(types = ApiSearchForm.class)
public class ApiController {
  private static final Logger log = LoggerFactory.getLogger(ApiController.class);
  private static final String COOKIE_NAME_PAGE_SIZE = "api.pageSize";
  private static final CookieGenerator pageSizeCookieGenerator;
  private static final List<String> keyExtractors = Collections.unmodifiableList(Stream.of(
      "jsonPath"
      , "XPath"
      , "fixedLength"
      , "pathVariable"
      , "parameter"
      , "header"
      , "cookie"
  ).map(name -> name + "KeyExtractor").collect(Collectors.toList()));
  private static final List<String> keyGeneratingStrategies = Stream.of(KeyGeneratingStrategy.values())
      .map(KeyGeneratingStrategy::name).collect(Collectors.toList());

  static {
    pageSizeCookieGenerator = new CookieGenerator();
    pageSizeCookieGenerator.setCookieName(COOKIE_NAME_PAGE_SIZE);
  }

  private final ApiService service;
  private final ImportSupport importSupport;
  private final PaginationSupport paginationSupport;
  private final DownloadSupport downloadSupport;
  private final JsonSupport jsonSupport;

  public ApiController(ApiService service, ImportSupport importSupport, PaginationSupport paginationSupport, DownloadSupport downloadSupport, JsonSupport jsonSupport) {
    this.service = service;
    this.importSupport = importSupport;
    this.paginationSupport = paginationSupport;
    this.downloadSupport = downloadSupport;
    this.jsonSupport = jsonSupport;
  }

  @ModelAttribute("apiSearchForm")
  public ApiSearchForm setUpSearchForm() {
    return new ApiSearchForm();
  }

  @ModelAttribute("keyExtractors")
  public List<String> keyExtractors() {
    return keyExtractors;
  }

  @ModelAttribute("keyGeneratingStrategies")
  public List<String> keyGeneratingStrategies() {
    return keyGeneratingStrategies;
  }

  @GetMapping
  public String list(@Validated ApiSearchForm form, BindingResult result,
                     Pageable pageable,
                     @RequestParam(name = Pagination.PARAM_NAME_SIZE_IN_PAGE, defaultValue = "0") int paramPageSize,
                     @CookieValue(name = COOKIE_NAME_PAGE_SIZE, defaultValue = "0") int cookiePageSize,
                     @RequestParam MultiValueMap<String, String> requestParams,
                     Model model, HttpServletResponse response) {
    int pageSize = paginationSupport.decidePageSize(pageable, paramPageSize, cookiePageSize);
    paginationSupport.storePageSize(pageSize, model, response, pageSizeCookieGenerator);

    if (result.hasErrors()) {
      return "api/list";
    }
    Page<Api> page = service.findAll(form.getPath(), form.getMethod(), form.getDescription(),
        paginationSupport.decidePageable(pageable, pageSize));
    if (!page.hasContent()) {
      model.addAttribute(InfoMessage.builder().code(MessageCode.DATA_NOT_FOUND).build());
    }
    model.addAttribute(new Pagination(page, requestParams));
    return "api/list";
  }

  @PostMapping(params = "delete")
  public String delete(@RequestParam List<Integer> ids, RedirectAttributes redirectAttributes) {
    service.delete(ids);
    redirectAttributes.addFlashAttribute(SuccessMessage.builder().code(MessageCode.DATA_HAS_BEEN_DELETED).build());
    return "redirect:/manager/apis";
  }

  @GetMapping(path = "create")
  public String createForm(Model model) {
    model.addAttribute(new ApiForm());
    return "api/form";
  }


  @PostMapping(path = "create")
  public String create(@Validated ApiForm form, BindingResult result, Model model, RedirectAttributes redirectAttributes) throws JsonProcessingException {
    if (result.hasErrors()) {
      return "api/form";
    }
    Api api = new Api();
    BeanUtils.copyProperties(form, api);
    BeanUtils.copyProperties(form.getProxy(), api.getProxy());
    api.setExpressions(jsonSupport.toJson(form.getExpressions()));
    try {
      service.create(api);
    } catch (DuplicateKeyException e) {
      model.addAttribute(ErrorMessage.builder().code(MessageCode.DATA_ALREADY_EXISTS).build());
      return "api/form";
    }
    redirectAttributes.addAttribute("id", api.getId());
    redirectAttributes.addFlashAttribute(SuccessMessage.builder().code(MessageCode.DATA_HAS_BEEN_CREATED).build());
    return "redirect:/manager/apis/{id}";
  }


  @GetMapping(path = "{id}")
  public String editForm(@PathVariable int id, Model model, RedirectAttributes redirectAttributes) {
    Api api = service.findOne(id);
    if (api == null) {
      redirectAttributes.addFlashAttribute(ErrorMessage.builder().code(MessageCode.DATA_NOT_FOUND).build());
      return "redirect:/manager/apis";
    }
    if (api.getKeyedResponseNumber() != 0) {
      model.addAttribute(InfoMessage.builder().code(MessageCode.KEYED_RESPONSE_EXISTS).build());
    }
    ApiForm form = new ApiForm();
    BeanUtils.copyProperties(api, form);
    BeanUtils.copyProperties(api.getProxy(), form.getProxy());
    form.setExpressions(jsonSupport.toList(api.getExpressions()));
    model.addAttribute(api);
    model.addAttribute(form);
    return "api/form";
  }


  @PostMapping(path = "{id}", params = "update")
  public String edit(@PathVariable int id, @Validated ApiForm form, BindingResult result, Model model, RedirectAttributes redirectAttributes) throws JsonProcessingException {
    if (result.hasErrors()) {
      model.addAttribute(service.findOne(id));
      return "api/form";
    }
    Api api = new Api();
    BeanUtils.copyProperties(form, api);
    BeanUtils.copyProperties(form.getProxy(), api.getProxy());
    api.setExpressions(jsonSupport.toJson(form.getExpressions()));
    service.update(id, api);
    redirectAttributes.addFlashAttribute(SuccessMessage.builder().code(MessageCode.DATA_HAS_BEEN_UPDATED).build());
    return "redirect:/manager/apis/{id}";
  }

  @PostMapping(path = "{id}", params = "delete")
  public String delete(@PathVariable int id, RedirectAttributes redirectAttributes) {
    service.delete(id);
    redirectAttributes.addFlashAttribute(SuccessMessage.builder().code(MessageCode.DATA_HAS_BEEN_DELETED).build());
    return "redirect:/manager/apis";
  }

  @PostMapping(params = "export")
  public ResponseEntity<List<Api>> exportApis() {
    List<Api> apis = service.findAllForExport();
    HttpHeaders headers = new HttpHeaders();
    downloadSupport.addContentDisposition(headers, "exportApis.json");
    return ResponseEntity
        .status(HttpStatus.OK)
        .contentType(MediaType.APPLICATION_JSON_UTF8)
        .headers(headers)
        .body(apis);
  }

  @PostMapping(params = "import")
  public String importApis(@RequestParam MultipartFile file, @RequestParam(defaultValue = "false") boolean override, RedirectAttributes redirectAttributes) throws IOException {
    if (!StringUtils.hasLength(file.getOriginalFilename())) {
      redirectAttributes.addFlashAttribute(ErrorMessage.builder().code(MessageCode.IMPORT_FILE_NOT_SELECTED).build());
      return "redirect:/manager/apis";
    }
    if (file.getSize() == 0) {
      redirectAttributes.addFlashAttribute(ErrorMessage.builder().code(MessageCode.IMPORT_FILE_EMPTY).build());
      return "redirect:/manager/apis";
    }
    List<Api> newApis;
    try {
      newApis = jsonSupport.toApiList(file.getInputStream());
    } catch (JsonParseException | JsonMappingException e) {
      log.warn(e.getMessage(), e);
      redirectAttributes.addFlashAttribute(ErrorMessage.builder().code(MessageCode.INVALID_JSON).build());
      return "redirect:/manager/apis";
    }
    if (newApis.isEmpty()) {
      redirectAttributes.addFlashAttribute(ErrorMessage.builder().code(MessageCode.IMPORT_DATA_EMPTY).build());
      return "redirect:/manager/apis";
    }

    List<Api> ignoredApis = new ArrayList<>();
    newApis.forEach(newApi -> {
      Integer id = service.findIdByUk(newApi.getPath(), newApi.getMethod());
      if (id == null) {
        service.create(newApi);
      } else if (override) {
        service.update(id, newApi);
      } else {
        ignoredApis.add(newApi);
      }
    });
    importSupport.storeProcessingResultMessages(redirectAttributes, newApis, ignoredApis);
    return "redirect:/manager/apis";
  }

}
