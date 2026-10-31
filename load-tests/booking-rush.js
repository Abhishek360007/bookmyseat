// Ticket-release rush: 300 users each try to grab 2 random seats out of the same 50, all at once,
// through the real HTTP API (hold -> confirm). Afterwards every confirmed booking is checked: no seat may be sold twice.
// The app must allow many registrations/logins from one IP for this test (raise the rate limits on a local container).
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, login, authed, createShowWithSeats } from './lib.js';

const USERS = 300;
const holdsOk = new Counter('holds_ok');
const holdsRejected = new Counter('holds_rejected');
const bookingsOk = new Counter('bookings_ok');
const confirmsRejected = new Counter('confirms_rejected');
const unexpected = new Counter('unexpected_responses');

export const options = {
	setupTimeout: '300s',
	scenarios: {
		rush: { executor: 'per-vu-iterations', vus: USERS, iterations: 1, maxDuration: '60s' },
	},
	thresholds: {
		unexpected_responses: ['count==0'],
		checks: ['rate==1'],
	},
	summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
	const admin = login(__ENV.ADMIN_EMAIL, __ENV.ADMIN_PASSWORD);
	const show = createShowWithSeats(admin, `Rush ${Date.now()}`, 5, 10);

	// Register and log in the users in parallel batches; tokens are handed to the virtual users
	const runId = Date.now();
	const tokens = [];
	for (let start = 0; start < USERS; start += 50) {
		const ids = [...Array(Math.min(50, USERS - start)).keys()].map((i) => start + i);
		const body = (i) => JSON.stringify({ name: `Rush ${i}`, email: `rush-${runId}-${i}@example.com`, password: 'Rush-User-Pw-123' });
		http.batch(ids.map((i) => ['POST', `${BASE_URL}/api/auth/register`, body(i), { headers: { 'Content-Type': 'application/json' } }]));
		const logins = http.batch(ids.map((i) => ['POST', `${BASE_URL}/api/auth/login`,
			JSON.stringify({ email: `rush-${runId}-${i}@example.com`, password: 'Rush-User-Pw-123' }),
			{ headers: { 'Content-Type': 'application/json' } }]));
		logins.forEach((r) => tokens.push(r.json('accessToken')));
	}
	return { admin, showId: show.showId, seatIds: show.seatIds, tokens };
}

export default function (data) {
	const token = data.tokens[__VU - 1];
	const a = data.seatIds[Math.floor(Math.random() * data.seatIds.length)];
	let b = a;
	while (b === a) b = data.seatIds[Math.floor(Math.random() * data.seatIds.length)];
	const body = JSON.stringify({ showId: data.showId, seatIds: [a, b] });

	const hold = http.post(`${BASE_URL}/api/bookings/hold`, body, { ...authed(token), tags: { name: 'hold' } });
	if (hold.status === 409) { holdsRejected.add(1); return; }
	if (hold.status !== 201) { unexpected.add(1); return; }
	holdsOk.add(1);

	const confirm = http.post(`${BASE_URL}/api/bookings`, body, { ...authed(token), tags: { name: 'confirm' } });
	if (confirm.status === 201) bookingsOk.add(1);
	else if (confirm.status === 409) confirmsRejected.add(1);
	else unexpected.add(1);
}

// The proof: read every confirmed booking back and make sure no seat was sold twice
export function teardown(data) {
	const page = http.get(`${BASE_URL}/api/admin/shows/${data.showId}/bookings?size=100`, authed(data.admin)).json();
	const seats = page.content.flatMap((booking) => booking.seats);
	const summary = http.get(`${BASE_URL}/api/admin/shows/${data.showId}/summary`, authed(data.admin)).json();
	console.log(`confirmed bookings=${page.totalElements}, seats sold=${seats.length}, distinct=${new Set(seats).size}, ` +
		`summary booked=${summary.booked}, revenue=${summary.revenue}`);

	const ok = check(null, {
		'no seat sold twice': () => new Set(seats).size === seats.length,
		'summary matches bookings': () => summary.booked === seats.length && summary.confirmedBookings === page.totalElements,
		'every booking has 2 seats at 500.00': () => page.content.every((bk) => bk.seats.length === 2 && bk.totalAmount === 500),
	});
	if (!ok) fail('double booking detected');
}
