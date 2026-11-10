package dev.hookrelay.server.eventtype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hookrelay.server.common.ApiException;
import org.junit.jupiter.api.Test;

class SchemaValidatorTest {

    private final SchemaValidator validator = new SchemaValidator();
    private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"id\"]}";

    @Test
    void validPayloadHasNoErrors() {
        assertThat(validator.validate(SCHEMA, "{\"id\":1}")).isEmpty();
    }

    @Test
    void invalidPayloadListsErrors() {
        assertThat(validator.validate(SCHEMA, "{\"name\":\"x\"}")).singleElement().asString().contains("id");
    }

    @Test
    void schemaThatBreaksTheMetaSchemaIsRejected() {
        assertThatThrownBy(() -> validator.checkSchema("{\"type\":5}"))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(400);
    }

    @Test
    void remoteRefsAreNeverFetched() {
        assertThatThrownBy(() -> validator.checkSchema("{\"$ref\":\"http://10.0.0.1/evil.json\"}"))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(400);
    }
}
