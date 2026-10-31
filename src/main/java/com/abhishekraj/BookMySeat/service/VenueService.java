package com.abhishekraj.BookMySeat.service;

import com.abhishekraj.BookMySeat.config.CacheConfig;
import com.abhishekraj.BookMySeat.dto.VenueRequest;
import com.abhishekraj.BookMySeat.dto.VenueResponse;
import com.abhishekraj.BookMySeat.entity.Venue;
import com.abhishekraj.BookMySeat.exception.ConflictException;
import com.abhishekraj.BookMySeat.exception.ResourceNotFoundException;
import com.abhishekraj.BookMySeat.repository.ShowRepository;
import com.abhishekraj.BookMySeat.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VenueService {

	private final VenueRepository venueRepository;
	private final ShowRepository showRepository;

	@Transactional
	public VenueResponse create(VenueRequest request) {
		Venue venue = new Venue();
		venue.setName(request.name());
		venue.setCity(request.city());
		venue.setAddress(request.address());
		return VenueResponse.from(venueRepository.save(venue));
	}

	@Transactional(readOnly = true)
	public List<VenueResponse> getAll() {
		return venueRepository.findAll().stream().map(VenueResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public VenueResponse getById(Long id) {
		return VenueResponse.from(findVenue(id));
	}

	// Cached shows and search results include the venue name and city
	@CacheEvict(cacheNames = {CacheConfig.SHOW, CacheConfig.SHOW_SEARCH}, allEntries = true)
	@Transactional
	public VenueResponse update(Long id, VenueRequest request) {
		Venue venue = findVenue(id);
		venue.setName(request.name());
		venue.setCity(request.city());
		venue.setAddress(request.address());
		return VenueResponse.from(venue);
	}

	@Transactional
	public void delete(Long id) {
		Venue venue = findVenue(id);
		if (showRepository.existsByVenueId(id)) {
			throw new ConflictException("Cannot delete venue " + id + " because it has shows. Delete its shows first.");
		}
		venueRepository.delete(venue);
	}

	private Venue findVenue(Long id) {
		return venueRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Venue not found with id " + id));
	}
}
