// Read-heavy traffic: what most visitors do. 50 virtual users search, open a show and load its seat map.
//   docker run --rm -i --network <net> -e BASE_URL=... -e ADMIN_EMAIL=... -e ADMIN_PASSWORD=... grafana/k6:2.3.0 run - < load-tests/browse.js
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, login, createShowWithSeats } from './lib.js';

export const options = {
	setupTimeout: '120s',
	scenarios: {
		browse: {
			executor: 'ramping-vus',
			stages: [
				{ duration: '10s', target: 50 },
				{ duration: '40s', target: 50 },
				{ duration: '5s', target: 0 },
			],
		},
	},
	thresholds: {
		http_req_failed: ['rate<0.01'],
		http_req_duration: ['p(95)<500'],
		// Per-endpoint lines in the summary, so caching can be compared endpoint by endpoint
		'http_req_duration{name:search}': ['p(95)<500'],
		'http_req_duration{name:show}': ['p(95)<500'],
		'http_req_duration{name:seat map}': ['p(95)<500'],
	},
	summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
	const admin = login(__ENV.ADMIN_EMAIL, __ENV.ADMIN_PASSWORD);
	return createShowWithSeats(admin, 'Browse Load Test', 5, 10);
}

export default function (data) {
	const search = http.get(`${BASE_URL}/api/shows/search?city=loadtown&size=20`, { tags: { name: 'search' } });
	check(search, { 'search 200': (r) => r.status === 200 });

	const show = http.get(`${BASE_URL}/api/shows/${data.showId}`, { tags: { name: 'show' } });
	check(show, { 'show 200': (r) => r.status === 200 });

	const seats = http.get(`${BASE_URL}/api/shows/${data.showId}/seats`, { tags: { name: 'seat map' } });
	check(seats, { 'seat map 200': (r) => r.status === 200 });
}
