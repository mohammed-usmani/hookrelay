package dev.hookrelay.server.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class ProblemHandlerTest {

    record Body(@NotBlank String name) { }

    @RestController
    static class ThrowingController {
        @GetMapping("/missing")
        String missing() {
            throw new NotFoundException("endpoint");
        }

        @GetMapping("/conflict")
        String conflict() {
            throw new ConflictException("duplicate-name", "Name already used");
        }

        @PostMapping("/validate")
        String validate(@Valid @RequestBody Body body) {
            return "ok";
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new ProblemHandler())
            .build();

    @Test
    void notFoundIsProblemJson() throws Exception {
        mvc.perform(get("/missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://hookrelay.dev/problems/not-found"))
                .andExpect(jsonPath("$.detail").value("endpoint not found"));
    }

    @Test
    void conflictCarriesSlug() throws Exception {
        mvc.perform(get("/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://hookrelay.dev/problems/duplicate-name"));
    }

    @Test
    void validationErrorsListFields() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://hookrelay.dev/problems/validation"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void unreadableBodyIsBadRequest() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://hookrelay.dev/problems/malformed-request"));
    }
}
