package com.springrag.document;

import static com.springrag.auth.CurrentUser.id;

import com.springrag.common.PageResult;
import com.springrag.common.RagProperties;
import com.springrag.common.RateLimiter;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/collections/{collectionId}/documents")
public class DocumentController {

    private final DocumentService service;
    private final RateLimiter limiter;
    private final RagProperties props;

    public DocumentController(DocumentService service, RateLimiter limiter, RagProperties props) {
        this.service = service;
        this.limiter = limiter;
        this.props = props;
    }

    /** 202: the document is stored with status PROCESSING; parsing and embedding continue in the background. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentView upload(@AuthenticationPrincipal Jwt jwt, @PathVariable long collectionId,
                               @RequestPart("file") MultipartFile file) {
        // Every upload costs embedding calls, so it has its own per-user budget.
        limiter.check("upload:" + id(jwt), props.rateLimit().uploadPerMinute());
        return service.upload(id(jwt), collectionId, file);
    }

    /** Newest first. Paged with ?page=0&size=50 (size 1-200); the total count is in the X-Total-Count header. */
    @GetMapping
    public ResponseEntity<List<DocumentView>> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long collectionId,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "" + PageResult.DEFAULT_SIZE) int size) {
        PageResult<DocumentView> result = service.list(id(jwt), collectionId, page, size);
        return ResponseEntity.ok().header(PageResult.TOTAL_HEADER, String.valueOf(result.total())).body(result.items());
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long collectionId,
                       @PathVariable long documentId) {
        service.delete(id(jwt), collectionId, documentId);
    }
}
