package com.group4.wxai.service.capability;

public record CapabilitySpec(
        String name,
        String description,
        String inputJsonSchema,
        CapabilityKind kind
) {

    public CapabilitySpec {
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        inputJsonSchema = inputJsonSchema == null ? "" : inputJsonSchema;
    }
}
