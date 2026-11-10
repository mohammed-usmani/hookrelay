package dev.hookrelay.server.eventtype;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.hookrelay.server.common.ApiException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** JSON Schema (2020-12) checks for event payloads. Never fetches remote $refs. */
@Component
public class SchemaValidator {

    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            b -> b.schemaLoader(l -> l.fetchRemoteResources(false)));
    private final Schema metaSchema =
            registry.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
    // ponytail: compiled schemas cached by their text, unbounded; bounded by distinct schemas in use.
    private final Map<String, Schema> compiled = new ConcurrentHashMap<>();

    /** Throws 400 if the text is not a usable JSON Schema. */
    public void checkSchema(String schemaJson) {
        List<Error> metaErrors;
        try {
            metaErrors = metaSchema.validate(schemaJson, InputFormat.JSON);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid-schema", "schema is not valid JSON");
        }
        if (!metaErrors.isEmpty()) {
            throw ApiException.badRequest("invalid-schema", "schema is not a valid JSON Schema: "
                    + metaErrors.get(0).getMessage());
        }
        try {
            // Compiling resolves $refs; any ref we can't resolve locally makes the schema unusable.
            registry.getSchema(schemaJson, InputFormat.JSON).validate("{}", InputFormat.JSON);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid-schema", "schema cannot be compiled: " + e.getMessage());
        }
    }

    /** Validation errors for the payload; empty means valid. */
    public List<String> validate(String schemaJson, String payloadJson) {
        Schema schema = compiled.computeIfAbsent(schemaJson, s -> registry.getSchema(s, InputFormat.JSON));
        return schema.validate(payloadJson, InputFormat.JSON).stream().map(Error::getMessage).toList();
    }
}
