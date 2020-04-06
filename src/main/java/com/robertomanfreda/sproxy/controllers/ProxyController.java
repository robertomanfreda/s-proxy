package com.robertomanfreda.sproxy.controllers;

import com.robertomanfreda.sproxy.exceptions.ProxyException;
import com.robertomanfreda.sproxy.http.Extractor;
import com.robertomanfreda.sproxy.services.ProxyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.util.EntityUtils;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
@RestController
@RequestMapping("/**")
@RequiredArgsConstructor
public class ProxyController {

    private final ProxyService proxyService;
    private final HttpServletRequest httpServletRequest;

    /**
     * Request has body                 No
     * Successful response has body     No
     * Safe 	                        Yes
     * Idempotent                       Yes
     * Cacheable                        Yes
     * Allowed in HTML forms            No
     *
     * @return {@link ResponseEntity}
     * @throws ProxyException // TODO ProxyException in HEAD
     * @throws IOException    // TODO IOException in HEAD
     */
    @RequestMapping(method = RequestMethod.HEAD)
    public ResponseEntity<?> head() throws ProxyException, IOException {
        HttpEntity<?> requestEntity = makeRequestEntity();
        HttpHead httpRequest = new HttpHead(Extractor.extractEntityUrl(httpServletRequest));
        return makeResponseEntity(proxyService.doProxy(requestEntity, httpRequest));
    }

    /**
     * Request has body                 No
     * Successful response has body     Yes
     * Safe 	                        Yes
     * Idempotent                       Yes
     * Cacheable                        Yes
     * Allowed in HTML forms            No
     *
     * @return {@link ResponseEntity}
     * @throws ProxyException // TODO ProxyException in GET
     * @throws IOException    // TODO IOException in GET
     */
    @RequestMapping(method = RequestMethod.GET, produces = MediaType.ALL_VALUE)
    public ResponseEntity<?> get() throws ProxyException, IOException {
        HttpEntity<?> requestEntity = makeRequestEntity();
        HttpGet httpRequest = new HttpGet(Extractor.extractEntityUrl(httpServletRequest));
        return makeResponseEntity(proxyService.doProxy(requestEntity, httpRequest));
    }

    /**
     * Request has body                 Yes
     * Successful response has body     Yes
     * Safe 	                        No
     * Idempotent                       No
     * Cacheable                        Only if freshness information is included
     * Allowed in HTML forms            Yes
     *
     * @return {@link ResponseEntity}
     * @throws ProxyException // TODO ProxyException in POST
     * @throws IOException    // TODO IOException in POST
     */
    @RequestMapping(method = RequestMethod.POST, consumes = MediaType.ALL_VALUE, produces = MediaType.ALL_VALUE)
    public ResponseEntity<?> post() throws ProxyException, IOException, ServletException {
        HttpEntity<?> requestEntity = makeRequestEntity();
        HttpPost httpRequest = new HttpPost(Extractor.extractEntityUrl(httpServletRequest));

        String type = httpServletRequest.getContentType();
        if (type.contains("application/x-www-form-urlencoded")) {
            List<NameValuePair> parameters = Extractor.extractFormParameters(httpServletRequest);
            if (parameters.size() > 0) {
                httpRequest.setEntity(new UrlEncodedFormEntity(parameters));
            }
        } else if (type.contains("multipart/form-data")) {
            Collection<Part> parts = httpServletRequest.getParts();
            if (parts.size() > 0) {
                MultipartEntityBuilder builder = MultipartEntityBuilder.create();
                builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);
                MediaType contentType = requestEntity.getHeaders().getContentType();
                if (null != contentType) {
                    builder.setBoundary(contentType.getParameter("boundary"));
                }

                for (Part part : parts) {
                    if (null != part.getContentType()) {
                        builder.addBinaryBody(
                                part.getName(), part.getInputStream().readAllBytes(),
                                ContentType.MULTIPART_FORM_DATA, part.getSubmittedFileName()
                        );
                    } else {
                        builder.addTextBody(part.getName(), new String(part.getInputStream().readAllBytes()));
                    }
                }

                httpRequest.setEntity(builder.build());
            }
        } else {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(httpServletRequest.getInputStream(), UTF_8)
            );

            String body = reader.lines().collect(Collectors.joining(System.lineSeparator()));
            if (body.length() > 0) {
                httpRequest.setEntity(new StringEntity(body));
            }
        }

        return makeResponseEntity(proxyService.doProxy(requestEntity, httpRequest));
    }

    private HttpEntity<?> makeRequestEntity() {
        HttpHeaders httpHeaders = Extractor.extractHttpHeaders(httpServletRequest);
        Map<String, String> urlParameters = Extractor.extractQueryParameters(httpServletRequest);
        return new HttpEntity<>(urlParameters, httpHeaders);
    }

    private ResponseEntity<?> makeResponseEntity(HttpResponse httpResponse) throws IOException {
        // Populating response body (some response has no response body so we return an empty string)
        String responseBody = null != httpResponse.getEntity() ? EntityUtils.toString(httpResponse.getEntity()) : "";

        // Populating response headers
        MultiValueMap<String, String> responseHeaders = new HttpHeaders();
        Stream.of(httpResponse.getAllHeaders()).forEach(header ->
                responseHeaders.add(header.getName(), header.getValue())
        );

        return new ResponseEntity<>(
                responseBody,
                responseHeaders,
                Objects.requireNonNull(HttpStatus.resolve(httpResponse.getStatusLine().getStatusCode()))
        );
    }

}
