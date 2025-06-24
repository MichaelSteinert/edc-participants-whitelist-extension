package org.eclipse.edc.mvd.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.edc.mvd.model.ServiceDescriptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Replaces every JSON field called “title” with the constant "xxx". */
public final class MaskTitleProcessor extends ServiceDescriptor implements ServiceProcessor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public InputStream apply(InputStream in) throws Exception {
        JsonNode root = MAPPER.readTree(in);

        if (root.isArray()) {
            ArrayNode arr = (ArrayNode) root;
            arr.forEach(MaskTitleProcessor::mask);
        } else {
            mask(root);
        }
        byte[] out = MAPPER.writeValueAsBytes(root);
        return new ByteArrayInputStream(out);
    }


    private static void mask(JsonNode n) {
        if (n.has("title") && n.get("title").isTextual()) {
            ((ObjectNode) n)
                    .put("title", "xxx");
        }
        n.elements().forEachRemaining(MaskTitleProcessor::mask);
    }
}