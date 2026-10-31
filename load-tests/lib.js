// Shared helpers for the k6 load tests. Everything goes through the public HTTP API.
import http from 'k6/http';
import { check, fail } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function login(email, password) {
	const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ email, password }), { headers: JSON_HEADERS });
	if (!check(res, { 'login 200': (r) => r.status === 200 })) {
		fail(`login failed for ${email}: ${res.status} ${res.body}`);
	}
	return res.json('accessToken');
}

export function authed(token) {
	return { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } };
}

// Creates a venue, a show starting tomorrow and rows x perRow seats; returns { showId, seatIds }
export function createShowWithSeats(adminToken, title, rows, perRow) {
	const venue = http.post(`${BASE_URL}/api/venues`,
		JSON.stringify({ name: `${title} Venue`, city: 'Loadtown' }), authed(adminToken));
	const start = new Date(Date.now() + 24 * 3600 * 1000).toISOString().replace(/\.\d+Z$/, 'Z');
	const show = http.post(`${BASE_URL}/api/shows`,
		JSON.stringify({ title, startTime: start, price: 250.00, venueId: venue.json('id') }), authed(adminToken));
	if (show.status !== 201) {
		fail(`show creation failed: ${show.status} ${show.body}`);
	}
	const seats = http.post(`${BASE_URL}/api/shows/${show.json('id')}/seats`,
		JSON.stringify({ rows, seatsPerRow: perRow }), authed(adminToken));
	return { showId: show.json('id'), seatIds: seats.json().map((s) => s.id) };
}
