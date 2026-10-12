package com.springrag.collection;

import static com.springrag.auth.CurrentUser.id;

import com.springrag.common.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/collections")
public class CollectionController {

    public record CreateCollectionRequest(@NotBlank @Size(max = 120) String name) {
    }

    private final CollectionService service;

    public CollectionController(CollectionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CollectionSummary create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateCollectionRequest body) {
        return service.create(id(jwt), body.name());
    }

    /** Newest first. Paged with ?page=0&size=50 (size 1-200); the total count is in the X-Total-Count header. */
    @GetMapping
    public ResponseEntity<List<CollectionSummary>> list(@AuthenticationPrincipal Jwt jwt,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "" + PageResult.DEFAULT_SIZE) int size) {
        PageResult<CollectionSummary> result = service.list(id(jwt), page, size);
        return ResponseEntity.ok().header(PageResult.TOTAL_HEADER, String.valueOf(result.total())).body(result.items());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        service.delete(id(jwt), id);
    }
}
