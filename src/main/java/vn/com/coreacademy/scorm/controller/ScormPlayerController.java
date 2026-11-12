package vn.com.coreacademy.scorm.controller;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.scorm.dto.ScormDtos;
import vn.com.coreacademy.scorm.service.ScormService;
import vn.com.coreacademy.storage.web.ObjectDownloadHandler;

/** Only reachable on the isolated player connector; the normal LMS connector denies these paths. */
@RestController
@RequestMapping("/scorm-player/{token}")
@RequiredArgsConstructor
public class ScormPlayerController {
    private final ScormService service;
    private final ObjectDownloadHandler downloads;

    @GetMapping(value = "/launch", produces = MediaType.TEXT_HTML_VALUE)
    public String launch(@PathVariable String token) throws IOException { return service.player(token); }

    @GetMapping(value = "/runtime.js", produces = "text/javascript")
    public byte[] script(@PathVariable String token) throws IOException {
        service.player(token);
        return new ClassPathResource("scorm/runtime.js").getContentAsByteArray();
    }

    @GetMapping("/files/{*path}")
    public void file(@PathVariable String token, @PathVariable String path,
        HttpServletRequest request, HttpServletResponse response) throws IOException {
        downloads.write(service.file(token, path.substring(1)), request, response);
    }

    @PostMapping(value = "/runtime", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public BaseResponse<ScormDtos.RuntimeReceipt> runtime(@PathVariable String token,
        @Valid @RequestBody ScormDtos.RuntimeRequest request) {
        return BaseResponse.ok(service.runtime(token, request));
    }
}
