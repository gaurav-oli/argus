package com.argus.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer slice: typed JSON success (AC #1) + RFC 9457 problem+json error (AC #2). */
@WebMvcTest(SystemInfoController.class)
@Import(GlobalExceptionHandler.class)
class SystemInfoControllerTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void returnsTypedJson() throws Exception {
		mockMvc.perform(get("/api/system-info"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.name").value("argus"))
				.andExpect(jsonPath("$.version").exists())
				.andExpect(jsonPath("$.time").exists());
	}

	@Test
	void unknownKeyReturnsProblemDetail() throws Exception {
		mockMvc.perform(get("/api/system-info/does-not-exist"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Resource Not Found"))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.detail").exists());
	}
}
