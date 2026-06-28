# Welcome to Volunteer Monster 👹

Volunteer Monster is a platform designed to connect people with regional, flexible, and time-bound service projects. We automate the heavy lifting for non-profits and make it easy for volunteers to find meaningful projects.

However it is you'd like to help, we're thrilled to have you!

## Our Engineering Philosophy

Our stack is special for the same reason a PB&J is special: it’s simple, dependable, and gets the job done.

* **No bloat or black magic:** We build with clear, explicit patterns rather than tools that hide complexity behind the scenes.
* **Lean & purpose-driven:** If a technology doesn't directly serve a user need or keep our server footprint light, it doesn't make the cut.
* **Pragmatism over hype:** We measure success by real-world impact, not bandwagon tech trends.


## How We Build

To maintain our high standards, we rely on a carefully chosen set of tools.

* **Micronaut and GraalVM:** Our core application is built using an [optimized framework](https://micronaut.io/) that allows us to compile our project into [stupidly quick executables](https://www.graalvm.org/latest/reference-manual/native-image/) which can run anywhere (server, cloud, whatever).
* **Location & Data:** Since this platform revolves around physical locations, we're not going to try and reinvent the wheel. Instead we use an [a tried and true database extension](https://postgis.net/) to do this work quickly and correctly.
* **JTE front end:** We want fast, SEO-friendly, and easy to write and quick to load front ends, so we use [pure, server-rendered templates](https://jte.gg/) rather than heavy client-side scripts.
* **Compose Multiplatform:** For complex, logged-in dashboards and our future mobile applications, we use a [unified, shared interface toolkit](https://www.jetbrains.com/lp/compose-multiplatform/) that let's us write the same front end logic for ios, android, mac, windows, and linux. 
* **Identity Security:** We never store passwords or handle login security directly. We hand all of that high-liability work off to a [dedicated, self-hosted identity manager](https://goauthentik.io/) and simply enforce the rules it hands back to us, leaning into Micronaut's framework once again for simplicity and stability.

## Our Testing Culture

We do not care about vanity metrics like "100% test coverage". Instead, we care entirely about validating documented behavior. A failing test is not a problem to be "fixed" - it is a clear signal that our documentation and our code are no longer in sync, and it must be updated so that the two match once again. 

When you write tests for Volunteer Monster, you will use a [highly readable, specification-driven testing tool](https://spockframework.org/) that reads like plain English. If a feature or user journey is documented, it must be proven in a test. To ensure absolute reliability, all of our database tests run automatically against [completely isolated, temporary data environments](https://testcontainers.com/) that destroy themselves the moment the test finishes.

## Getting Started

To get your local environment running, you don't need a massive checklist. You just need to run our [scripted, isolated container setup](https://docs.docker.com/compose/) to spin up the database and infrastructure, and then launch the backend engine.

Grab a peanut butter sandwich and let's build something that helps people help people.