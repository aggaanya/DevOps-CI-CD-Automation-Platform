package com.cicd.platform.worker.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ArtifactInfo(String name, long sizeBytes, String path) {
}
