package com.triples.rougether.adminapi.characterpose.web;

import com.triples.rougether.adminapi.characterpose.dto.CharacterPoseAdminRequest;
import com.triples.rougether.adminapi.characterpose.dto.CharacterPoseAdminResponse;
import com.triples.rougether.adminapi.characterpose.service.CharacterPoseAdminService;
import com.triples.rougether.common.error.ErrorResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/character-poses")
public class CharacterPoseAdminController {

    private final CharacterPoseAdminService service;

    public CharacterPoseAdminController(CharacterPoseAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<CharacterPoseAdminResponse> list() {
        return service.list();
    }

    @PostMapping
    public CharacterPoseAdminResponse upsert(
            @Valid @RequestBody CharacterPoseAdminRequest request) {
        return service.upsert(request);
    }

    @DeleteMapping("/{poseId}")
    public ResponseEntity<Void> delete(@PathVariable Long poseId) {
        service.delete(poseId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleInvalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("CHARACTER_POSE_INVALID", exception.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("CHARACTER_POSE_NOT_FOUND", exception.getMessage()));
    }
}
