# Kaiju

Backend service for Volunteer Monster, a modern platform connecting volunteers with community initiatives.

## Project Status

> [!NOTE]
> **Work in Progress**: Kaiju is currently under active foundational development. Standalone execution guides and quick-start instructions will be added as deployment and service configurations stabilize.

## Why Volunteer Monster?

Nonprofits shouldn't have to choose between clunky legacy software and costly enterprise platforms. Kaiju is built to deliver real-world impact:

<details>
<summary><b>Hyper-Local Discovery</b>: Volunteers find initiatives right in their neighborhood without zip code guesswork.</summary>
<dl>
<dd>

All the math leans into two strengths: [PostGIS](https://postgis.net/)'s phenomenal spatial performance, and [Micronaut](https://micronaut.io/) and [GraalVM](https://www.graalvm.org/latest/reference-manual/native-image/)'s crazy speed. No reinventing the wheel, just pairing two technologies that work together beautifully.

</dd>
</dl>
</details>

<details>
<summary><b>Budget-Friendly Frugality</b>: Minimal server and memory overhead keeps hosting costs near zero.</summary>
<dl>
<dd>

Traditional servers eat up expensive memory just idling. By compiling ahead-of-time with [Micronaut AOT](https://micronaut-projects.github.io/micronaut-aot/latest/guide/) and [GraalVM](https://www.graalvm.org/latest/reference-manual/native-image/), Kaiju starts instantly and runs with a tiny memory footprint. Nonprofits can host it on low-cost cloud tiers without blowing donor funds on giant hosting bills.

</dd>
</dl>
</details>

<details>
<summary><b>Trusted & Safe</b>: Multi-layer verification, moderation queues, and immutable audit logs keep communities safe.</summary>
<dl>
<dd>

Safety shouldn't require bureaucracy. Verified partner organizations get fast-tracked, while new community initiatives are routed to local coordinators who know the area before listings go live. Everything from volunteer approvals to role updates is safely logged, and separate volunteer records ensure attendee histories stay private and protected.

</dd>
</dl>
</details>

<details>
<summary><b>Fast & Resilient</b>: Sub-millisecond response times sustain major community drives and disaster mobilizations.</summary>
<dl>
<dd>

When an emergency response or major holiday drive brings thousands of volunteers to the platform at once, the system won't choke. Powered by [Java 25](https://openjdk.org/projects/jdk/25/)'s ultra-low-latency [Generational ZGC](https://openjdk.org/jeps/439) and a unified [PostgreSQL](https://www.postgresql.org/) database, searches and signups stay snappy under heavy traffic without spinning wheels or crashes.

</dd>
</dl>
</details>

## Code Quality & Tech Stack

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=PeanutButter-Unicorn_kaiju&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=PeanutButter-Unicorn_kaiju)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=PeanutButter-Unicorn_kaiju&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=PeanutButter-Unicorn_kaiju)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=PeanutButter-Unicorn_kaiju&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=PeanutButter-Unicorn_kaiju)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=PeanutButter-Unicorn_kaiju&metric=coverage)](https://sonarcloud.io/summary/new_code?id=PeanutButter-Unicorn_kaiju)

- **Framework**: [Micronaut 5](https://micronaut.io/) (Java 25)
- **Testing**: [Spock](https://spockframework.org/) and [Testcontainers](https://testcontainers.com/) with live PostgreSQL/PostGIS containers. See [Testing Guidelines](./TESTING.md).
- **Database**: PostgreSQL + PostGIS via [Flyway](https://documentation.red-gate.com/fd) and [HikariCP](https://micronaut-projects.github.io/micronaut-sql/latest/guide/index.html#jdbc). See [Database Schema](./database/DB.md).
- **Security**: [Micronaut Security](https://micronaut-projects.github.io/micronaut-security/latest/guide/index.html) JWT authentication via [authentik](https://goauthentik.io/).
- **Serialization & Validation**: Java 25 Records via [Micronaut Serialization](https://micronaut-projects.github.io/micronaut-serialization/latest/guide/) (compile-time, reflection-free) and [Jakarta Validation](https://micronaut-projects.github.io/micronaut-validation/latest/guide/).
- **Build & Optimization**: Gradle with [Micronaut AOT](https://micronaut-projects.github.io/micronaut-aot/latest/guide/) and [GraalVM](https://graalvm.github.io/native-build-tools/latest/gradle-plugin.html).
- **Want to Help?** See [Contributing](./CONTRIBUTING.md).

<details>
<summary><b>Live Test Execution</b>: Watch Spock and Testcontainers boot PostGIS, run migrations, and test security in about a minute.</summary>
<dl>
<dd>

![Live terminal recording of Spock and Testcontainers running ProjectSecurityMatrixSpec](./testing-call.gif)

#### Why This Matters

* **Zero Flaky Mocks**: We test against real infrastructure. A single Gradle command spins up a genuine [PostgreSQL](https://www.postgresql.org/) container with [PostGIS](https://postgis.net/) spatial extensions via [Testcontainers](https://testcontainers.com/).
* **Instant Database Setup**: [Flyway](https://documentation.red-gate.com/fd) applies 10 complete schema migrations on the fly, setting up tables, geographic indexes, seed personas, and timezone boundaries automatically.
* **Super Thorough Testing**: The suite executes deep security checks across regional jurisdictions, organization roles, and physical boundary coordinates.
* **Cold Start to Teardown in About a Minute**: While the animation playback is slightly sped up for quick viewing, the real Gradle timer in the terminal shows the entire sequence finishes in about a minute: spinning up Docker, applying 10 database migrations, running integration tests, and tearing down clean.
* **One Simple Command**: No manual database configuration, no port conflicts, and no leftover test junk.

</dd>
</dl>
</details>
