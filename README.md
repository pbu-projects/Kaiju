 <h1 style="display: inline-block;"><img src="https://media.tenor.com/i8vhkDUi1wsAAAAi/wave-joe.gif" alt="Waving" style="height: 4em; vertical-align: middle;">We're Volunteer Monster </h1>

 <p>Great projects need people, and people want to do good. Volunteer Monster connects the two. We empower both sides of
 community action: equipping organizers with the volunteers they need to get things done, and giving individuals an easy
 way to put their time to good use.</p>

However it is you'd like to help, we're thrilled to have you!

## Our Design Priorities

We can summarize our priorities in three main points.

#### No bloat

We deliver what's needed and keep it simple. We do this by clearly identifying target audiences and deliverables, we
course correct at every iteration, and
_[we do not do waterfall in sprints](https://www.linkedin.com/pulse/you-using-agile-just-splitting-waterfall-sprints-samruddhi-there-1a7le/)_

#### No Bandwagon

Our toolset is based on real-world impact, not bandwagon tech trends. Building with Java means we're building with the
same foundation
that [runs the world](https://onyxwizard.medium.com/java-is-dead-but-it-still-runs-the-world-ee68ed6e546f).

#### User Focus

Whether a piece of software is running on highly optimized machine code or a literal hamster wheel doesn't matter to us.
If the [test suite](#Our-Testing-Culture) accurately personifies the user and passes, keep the hamster running.

## How We Build

To maintain our high standards, we rely on a carefully chosen set of tools.

#### Micronaut and GraalVM

Our core application is built using an [optimized framework](https://micronaut.io/) that allows us to compile our
project into [stupidly quick executables](https://www.graalvm.org/latest/reference-manual/native-image/) which can run
anywhere (server, cloud, whatever).

#### Location & Data

Since this platform revolves around physical locations, we're not going to try and reinvent the wheel. Instead, we
use [a tried and true database extension](https://postgis.net/) to do this work quickly and correctly.

#### JTE front end

We want fast, SEO-friendly, and easy to write and quick to load front ends, so we
use [pure, server-rendered templates](https://jte.gg/) rather than heavy client-side scripts.

#### Compose Multiplatform

For complex, logged-in dashboards and our future mobile applications, we use
a [unified, shared interface toolkit](https://www.jetbrains.com/lp/compose-multiplatform/) that lets us write the same
front end logic for ios, android, mac, windows, and linux.

#### Identity Security

We never store passwords or handle login security directly. We hand all of that high-liability work off to
a [dedicated, self-hosted identity manager](https://goauthentik.io/) and simply enforce the rules it hands back to us,
leaning into Micronaut's framework once again for simplicity and stability.

## Our Testing Culture

We do not care about vanity metrics like "100% test coverage". Instead, we care entirely about validating documented behavior. A failing test is not a problem to be "fixed" - it is a clear signal that our documentation and our code are no longer in sync, and it must be updated so that the two match once again. 

When you write tests for Volunteer Monster, you will use
a [highly readable, specification-driven testing tool called Spock](https://spockframework.org/) that reads like plain
English. If a feature or user journey is documented, it must be proven in a test. To ensure absolute reliability, all of
our database tests run automatically
against [completely isolated, temporary data environments](https://testcontainers.com/) that destroy themselves the
moment the test finishes.
