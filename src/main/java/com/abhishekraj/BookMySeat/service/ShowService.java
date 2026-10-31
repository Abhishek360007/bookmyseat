package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.config.CacheConfig;
import com.abhishekraj.BookMySeat.dto.PageResponse;
import com.abhishekraj.BookMySeat.dto.ShowRequest;
import com.abhishekraj.BookMySeat.dto.ShowResponse;
import com.abhishekraj.BookMySeat.entity.Show;
import com.abhishekraj.BookMySeat.entity.Venue;
import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.SeatRepository;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.ShowSpecifications;
import com.abhishekraj.BookMySeat.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ShowService {

	private final ShowRepository showRepository;
	private final VenueRepository venueRepository;
	private final SeatRepository seatRepository;
	private final Clock clock;

	@CacheEvict(cacheNames = CacheConfig.SHOW_SEARCH, allEntries = true)
	@Transactional
	public ShowResponse create(ShowRequest request) {
		Show show = new Show();
		applyRequest(show, request);
		return ShowResponse.from(showRepository.save(show));
	}

	/** Upcoming shows by default; every filter is optional. */
	@Cacheable(CacheConfig.SHOW_SEARCH)
	@Transactional(readOnly = true)
	public PageResponse<ShowResponse> search(String city, String text, Instant from, Instant to, Pageable pageable) {
		Instant start = from != null ? from : Instant.now(clock);
		if (to != null && !to.isAfter(start)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'to' must be after 'from'");
		}

		List<Specification<Show>> filters = new ArrayList<>();
		filters.add(ShowSpecifications.startsAtOrAfter(start));
		if (to != null) filters.add(ShowSpecifications.startsBefore(to));
		if (city != null && !city.isBlank()) filters.add(ShowSpecifications.inCity(city));
		if (text != null && !text.isBlank()) filters.add(ShowSpecifications.titleContains(text));

		return PageResponse.of(showRepository.findAll(Specification.allOf(filters), pageable), ShowResponse::from);
	}

	@Transactional(readOnly = true)
	public List<ShowResponse> getAll(Long venueId) {
		List<Show> shows = (venueId == null)
				? showRepository.findAll()
				: showRepository.findByVenueId(venueId);
		return shows.stream().map(ShowResponse::from).toList();
	}

	@Cacheable(cacheNames = CacheConfig.SHOW, key = "#id")
	@Transactional(readOnly = true)
	public ShowResponse getById(Long id) {
		return ShowResponse.from(findShow(id));
	}

	@Caching(evict = {
			@CacheEvict(cacheNames = CacheConfig.SHOW, key = "#id"),
			@CacheEvict(cacheNames = CacheConfig.SHOW_SEARCH, allEntries = true)})
	@Transactional
	public ShowResponse update(Long id, ShowRequest request) {
		Show show = findShow(id);
		applyRequest(show, request);
		return ShowResponse.from(show);
	}

	@Caching(evict = {
			@CacheEvict(cacheNames = CacheConfig.SHOW, key = "#id"),
			@CacheEvict(cacheNames = CacheConfig.SHOW_SEARCH, allEntries = true)})
	@Transactional
	public void delete(Long id) {
		Show show = findShow(id);
		if (seatRepository.existsByShowId(id)) {
			throw new ConflictException("Cannot delete show " + id + " because it has seats.");
		}
		showRepository.delete(show);
	}

	private void applyRequest(Show show, ShowRequest request) {
		Venue venue = venueRepository.findById(request.venueId())
				.orElseThrow(() -> new ResourceNotFoundException("Venue not found with id " + request.venueId()));
		show.setTitle(request.title());
		show.setStartTime(request.startTime());
		show.setPrice(request.price());
		show.setVenue(venue);
	}

	private Show findShow(Long id) {
		return showRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Show not found with id " + id));
	}
}
