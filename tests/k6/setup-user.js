import http from 'k6/http';
import { check } from 'k6';

export default function () {
    const email = `loadtest_${Date.now()}@example.com`;

    const response = http.post(
        'http://localhost/api/v1/auth/register',
        JSON.stringify({
            email: email,
            password: 'password123'
        }),
        {
            headers: {
                'Content-Type': 'application/json'
            }
        }
    );

    const registered = check(response, {
        'registration returned 201': (r) => r.status === 201,
    });

    if (!registered) {
        console.error(`Registration failed.`);
        console.error(`Status: ${response.status}`);
        console.error(`Body: ${response.body}`);
        return;
    }

    const body = JSON.parse(response.body);

    console.log(`LOAD_TEST_EMAIL=${email}`);
    console.log(`LOAD_TEST_TOKEN=${body.accessToken}`);
}