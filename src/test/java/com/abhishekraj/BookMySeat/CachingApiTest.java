package com.abhishekraj.BookMySeat;

import com.abhishekraj.BookMySeat.config.CacheConfig;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.VenueRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Caching must never serve stale data after an admin edit. Same configuration as BookingApiTest (shared context).
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class CachingApiTest {

	@Autowired MockMvc mvc;
	@Autowired CacheManager cacheManager;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;

	private String admin;
	private int venueId;
	private int showId;
	private String start;

	@BeforeEach
	void setUp() throws Exception {
		cacheManager.getCache(CacheConfig.SHOW).clear();
		cacheManager.getCache(CacheConfig.SHOW_SEARCH).clear();
		admin = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"api-test-admin@example.com\",\"password\":\"Api-Test-Admin-Pw-1\"}"))
				.andReturn().getResponse().getContentAsString(), "$.accessToken");
		venueId = JsonPath.read(send(post("/api/venues"), "{\"name\":\"Cache Venue\",\"city\":\"Cachepur\"}"), "$.id");
		start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
		showId = JsonPath.read(send(post("/api/shows"), showJson("Cached Show", "250.00")), "$.id");
	}

	@AfterEach
	void cleanUp() {
		showRepository.deleteAll();
		venueRepository.deleteAll();
	}

	@Test
	void showIsCachedAfterFirstRead() throws Exception {
		assertThat(cacheManager.getCache(CacheConfig.SHOW).get(showId)).isNull();

		mvc.perform(get("/api/shows/" + showId)).andExpect(status().isOk());

		assertThat(cacheManager.getCache(CacheConfig.SHOW).get(showId)).isNotNull();
		mvc.perform(get("/api/shows/" + showId)) // served from Redis, same JSON
				.andExpect(jsonPath("$.title").value("Cached Show"))
				.andExpect(jsonPath("$.price").value(250.00))
				.andExpect(jsonPath("$.startTime").value(start));
	}

	@Test
	void editingAShow_isVisibleImmediately() throws Exception {
		mvc.perform(get("/api/shows/" + showId)).andExpect(jsonPath("$.price").value(250.00));
		mvc.perform(get("/api/shows/search?city=cachepur")).andExpect(jsonPath("$.content[0].price").value(250.00));

		send(put("/api/shows/" + showId), showJson("Cached Show", "400.00"));

		mvc.perform(get("/api/shows/" + showId)).andExpect(jsonPath("$.price").value(400.00));
		mvc.perform(get("/api/shows/search?city=cachepur")).andExpect(jsonPath("$.content[0].price").value(400.00));
	}

	@Test
	void newShow_appearsInCachedSearchImmediately() throws Exception {
		mvc.perform(get("/api/shows/search?city=cachepur")).andExpect(jsonPath("$.totalElements").value(1));

		send(post("/api/shows"), showJson("Second Show", "300.00"));

		mvc.perform(get("/api/shows/search?city=cachepur")).andExpect(jsonPath("$.totalElements").value(2));
	}

	@Test
	void renamingTheVenue_updatesCachedShows() throws Exception {
		mvc.perform(get("/api/shows/" + showId)).andExpect(jsonPath("$.venueName").value("Cache Venue"));

		send(put("/api/venues/" + venueId), "{\"name\":\"Renamed Venue\",\"city\":\"Cachepur\"}");

		mvc.perform(get("/api/shows/" + showId)).andExpect(jsonPath("$.venueName").value("Renamed Venue"));
		mvc.perform(get("/api/shows/search?city=cachepur")).andExpect(jsonPath("$.content[0].venueName").value("Renamed Venue"));
	}

	private String showJson(String title, String price) {
		return "{\"title\":\"" + title + "\",\"startTime\":\"" + start + "\",\"price\":" + price + ",\"venueId\":" + venueId + "}";
	}

	private String send(MockHttpServletRequestBuilder request, String json) throws Exception {
		return mvc.perform(request.header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
	}
}
