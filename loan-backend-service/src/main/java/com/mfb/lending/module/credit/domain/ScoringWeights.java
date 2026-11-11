package com.mfb.lending.module.credit.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ScoringWeights(
        List<ScoringComponentSpec> components,
        Map<String, Map<String, BigDecimal>> categoricalScores
) {

    public ScoringWeights {
        components = components == null ? List.of() : List.copyOf(components);
        categoricalScores = categoricalScores == null ? Map.of() : Map.copyOf(categoricalScores);
    }
}
