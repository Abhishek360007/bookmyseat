package com.abhishekraj.BookMySeat;

import com.abhishekraj.BookMySeat.config.CacheConfig;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.entity.Venue;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.VenueRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Same configuration as BookingApiTest, so it reuses that cached context
@SpringBootTest(properties = {
		"app.admin.email=api-test-admin@example.com",
		"app.admin.password=Api-Test-Admin-Pw-1",
		"rate-limit.login.max-requests=1000",
		"rate-limit.register.max-requests=1000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ShowSearchApiTest {

	private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

	@Autowired MockMvc mvc;
	@Autowired ShowRepository showRepository;
	@Autowired VenueRepository venueRepository;
	@Autowired CacheManager cacheManager;

	@BeforeEach
	void setUp() {
		// Shows are saved straight through the repository here, which skips the API's cache eviction
		cacheManager.getCache(CacheConfig.SHOW_SEARCH).clear();
		Venue mumbai = venue("PVR Phoenix", "Mumbai");
		Venue pune = venue("INOX Pune", "Pune");
		show("Dune: Part Three", mumbai, 1);
		show("Dune Classic", pune, 2);
		show("Interstellar", mumbai, 3);
		show("100% Love", pune, 4);
		show("Dune: Already Started", mumbai, -1); // in the past: never returned by default
	}

	@AfterEach
	void cleanUp() {
		showRepository.deleteAll();
		venueRepository.deleteAll();
	}

	@Test
	void noFilters_returnsUpcomingShowsSoonestFirst() throws Exception {
		search("")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[*].title")
						.value(contains("Dune: Part Three", "Dune Classic", "Interstellar", "100% Love")))
				.andExpect(jsonPath("$.content[0].venueName").value("PVR Phoenix"))
				.andExpect(jsonPath("$.totalElements").value(4));
	}

	@Test
	void filtersCombine_cityIsCaseInsensitive() throws Exception {
		search("city=mumbai").andExpect(jsonPath("$.content[*].title")
				.value(contains("Dune: Part Three", "Interstellar")));

		search("city=MUMBAI&q=DUNE").andExpect(jsonPath("$.content[*].title")
				.value(contains("Dune: Part Three")));

		search("q=dune").andExpect(jsonPath("$.content[*].title")
				.value(containsInAnyOrder("Dune: Part Three", "Dune Classic")));
	}

	@Test
	void likeWildcardsAreTreatedAsText() throws Exception {
		// .param() so the '%' reaches the server as-is (a URL string would be encoded a second time)
		mvc.perform(get("/api/shows/search").param("q", "%"))
				.andExpect(jsonPath("$.content[*].title").value(contains("100% Love")));
		mvc.perform(get("/api/shows/search").param("q", "_"))
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void dateRange_andExplicitFromCanIncludePastShows() throws Exception {
		search("from=" + NOW.plus(2, ChronoUnit.DAYS).minusSeconds(60) + "&to=" + NOW.plus(3, ChronoUnit.DAYS).plusSeconds(60))
				.andExpect(jsonPath("$.content[*].title").value(contains("Dune Classic", "Interstellar")));

		search("q=already&from=" + NOW.minus(2, ChronoUnit.DAYS))
				.andExpect(jsonPath("$.content[*].title").value(contains("Dune: Already Started")));
	}

	@Test
	void paginationAndSorting() throws Exception {
		search("size=3&page=1")
				.andExpect(jsonPath("$.content[*].title").value(contains("100% Love")))
				.andExpect(jsonPath("$.totalElements").value(4))
				.andExpect(jsonPath("$.totalPages").value(2));

		search("sort=title,asc&size=2")
				.andExpect(jsonPath("$.content[*].title").value(contains("100% Love", "Dune Classic")));
	}

	@Test
	void invalidInput_is400() throws Exception {
		search("from=" + NOW.plus(5, ChronoUnit.DAYS) + "&to=" + NOW.plus(1, ChronoUnit.DAYS)).andExpect(status().isBadRequest());
		search("q=" + "x".repeat(101)).andExpect(status().isBadRequest());
		search("sort=nope").andExpect(status().isBadRequest());
		search("from=not-a-date").andExpect(status().isBadRequest());
	}

	private ResultActions search(String query) throws Exception {
		return mvc.perform(get("/api/shows/search?" + query));
	}

	private Venue venue(String name, String city) {
		Venue venue = new Venue();
		venue.setName(name);
		venue.setCity(city);
		return venueRepository.save(venue);
	}

	private void show(String title, Venue venue, int daysFromNow) {
		Show show = new Show();
		show.setTitle(title);
		show.setVenue(venue);
		show.setStartTime(NOW.plus(daysFromNow, ChronoUnit.DAYS));
		show.setPrice(new BigDecimal("250.00"));
		showRepository.save(show);
	}
}
