@param lol.pbu.kaiju.model.FeaturedProjectView featuredProject

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Volunteer Monster: Meaningful service, matched to what you bring</title>
    <link rel="icon" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><rect width='100' height='100' rx='24' fill='%234A2545'/><text x='50' y='65' font-family='system-ui,sans-serif' font-weight='800' font-size='44' fill='white' text-anchor='middle'>VM</text></svg>">
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&family=JetBrains+Mono:wght@500;600&family=Plus+Jakarta+Sans:wght@700;800&display=swap" rel="stylesheet">
    <link rel="stylesheet" href="/css/volunteer-monster.css" />
</head>
<body>
    <a href="#main-content" class="skip-link">Skip to content</a>

    <header class="vm-header">
        <div class="vm-header-inner">
            <a href="/" class="vm-brand" aria-label="Volunteer Monster Home">
                <span class="vm-brand-badge">VM</span>
                <span>Volunteer Monster</span>
            </a>

            <nav class="vm-nav-actions" aria-label="Primary navigation">
                <a href="/host" class="vm-nav-link">
                    <span class="vm-nav-label-full">Post an Opportunity</span>
                    <span class="vm-nav-label-short">Post</span>
                </a>
                <a href="/login" class="vm-btn vm-btn-outline">Sign In</a>
            </nav>
        </div>
    </header>

    <main id="main-content">
        <section class="vm-hero">
            <div class="vm-container">
                <div class="vm-hero-grid">
                    <div>
                        <div class="vm-eyebrow">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                                <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path>
                                <path d="m9 12 2 2 4-4"></path>
                            </svg>
                            <span>100% Free &middot; Skill-Matched &middot; Human-Reviewed</span>
                        </div>

                        <h1 class="vm-hero-title">Meaningful service, matched to what you bring.</h1>
                        <p class="vm-hero-subhead">
                            Discover vetted opportunities tailored to your skills, strengths, and schedule, with live open spots and zero account walls.
                        </p>

                        <form action="/projects" method="get" class="vm-entry-form" role="search" aria-label="Find volunteer opportunities">
                            <div class="vm-entry-field">
                                <label for="hero-q" class="vm-entry-label">Skills or cause</label>
                                <input
                                    id="hero-q"
                                    name="q"
                                    type="text"
                                    class="vm-entry-input"
                                    placeholder="Teaching, heavy lifting, organizing, design, food pantry..."
                                    autocomplete="off"
                                />
                            </div>
                            <div class="vm-entry-field">
                                <label for="hero-location" class="vm-entry-label">Where</label>
                                <input
                                    id="hero-location"
                                    name="location"
                                    type="text"
                                    class="vm-entry-input"
                                    placeholder="City, ZIP, or Remote"
                                    autocomplete="postal-code"
                                />
                            </div>
                            <button type="submit" class="vm-btn vm-btn-primary vm-entry-submit">
                                <span>Find Opportunities</span>
                                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                                    <path d="M5 12h14"></path>
                                    <path d="m12 5 7 7-7 7"></path>
                                </svg>
                            </button>
                        </form>
                    </div>

                    <aside class="vm-hero-emblem" aria-hidden="true">
                        <svg viewBox="0 0 300 260" fill="none" xmlns="http://www.w3.org/2000/svg">
                            <rect x="16" y="16" width="268" height="228" rx="14" fill="#F6F3EE" stroke="#DBD3CA" stroke-width="2" />
                            <path d="M36 82 C 88 52, 134 114, 190 86 C 228 66, 250 80, 264 70" stroke="#DBD3CA" stroke-width="1.5" />
                            <path d="M36 126 C 92 98, 140 160, 198 128 C 232 108, 250 124, 264 116" stroke="#DBD3CA" stroke-width="1.5" />
                            <path d="M36 172 C 90 144, 144 204, 204 174 C 236 158, 252 172, 264 166" stroke="#DBD3CA" stroke-width="1.5" />
                            <circle cx="150" cy="128" r="58" fill="#FFFFFF" stroke="#4A2545" stroke-width="2" />
                            <circle cx="150" cy="128" r="42" stroke="#965300" stroke-width="2" stroke-dasharray="5 5" />
                            <path d="M150 96 C135 96 123 108 123 123 C123 142 150 164 150 164 C150 164 177 142 177 123 C177 108 165 96 150 96 Z" fill="#F3EDF5" stroke="#4A2545" stroke-width="2" />
                            <circle cx="150" cy="122" r="9" fill="#965300" />
                            <rect x="52" y="34" width="84" height="22" rx="11" fill="#FDF6EC" stroke="#E8C89A" stroke-width="1.5" />
                            <circle cx="65" cy="45" r="4" fill="#965300" />
                            <line x1="76" y1="45" x2="122" y2="45" stroke="#965300" stroke-width="2" stroke-linecap="round" />
                            <rect x="164" y="202" width="96" height="24" rx="8" fill="#F3EDF5" stroke="#C8B9CC" stroke-width="1.5" />
                            <line x1="178" y1="214" x2="246" y2="214" stroke="#4A2545" stroke-width="2" stroke-linecap="round" />
                        </svg>
                    </aside>
                </div>
            </div>
        </section>

        <section class="vm-section" aria-labelledby="ways-heading">
            <div class="vm-container">
                <div class="vm-section-header">
                    <span class="vm-section-kicker">Choose Your Format</span>
                    <h2 id="ways-heading" class="vm-section-title">Three ways to participate</h2>
                </div>

                <div class="vm-triptych-grid">
                    <a href="/projects?type=IN_PERSON" class="vm-triptych-card">
                        <div class="vm-triptych-top">
                            <span class="vm-triptych-icon" aria-hidden="true">
                                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                    <path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z"></path>
                                    <circle cx="12" cy="10" r="3"></circle>
                                </svg>
                            </span>
                            <span class="vm-stamp">2-4 hr shifts</span>
                        </div>
                        <h3 class="vm-triptych-title">Show Up</h3>
                        <p class="vm-triptych-caption">Hands-on shifts at food pantries, parks, and shelters.</p>
                    </a>

                    <a href="/projects?type=DONATE" class="vm-triptych-card">
                        <div class="vm-triptych-top">
                            <span class="vm-triptych-icon" aria-hidden="true">
                                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                    <path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"></path>
                                    <path d="m3.3 7 8.7 5 8.7-5"></path>
                                    <path d="M12 22V12"></path>
                                </svg>
                            </span>
                            <span class="vm-stamp">Needed goods</span>
                        </div>
                        <h3 class="vm-triptych-title">Drop Off</h3>
                        <p class="vm-triptych-caption">Supply drives and collections with clear drop-off windows.</p>
                    </a>

                    <a href="/projects?type=REMOTE" class="vm-triptych-card">
                        <div class="vm-triptych-top">
                            <span class="vm-triptych-icon" aria-hidden="true">
                                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                    <rect width="18" height="12" x="3" y="4" rx="2" ry="2"></rect>
                                    <line x1="2" x2="22" y1="20" y2="20"></line>
                                </svg>
                            </span>
                            <span class="vm-stamp">Your schedule</span>
                        </div>
                        <h3 class="vm-triptych-title">From Home</h3>
                        <p class="vm-triptych-caption">Kit assembly, tutoring, and skill tasks you can do remotely.</p>
                    </a>
                </div>
            </div>
        </section>

        <section class="vm-section" aria-labelledby="diagram-heading">
            <div class="vm-container">
                <div class="vm-section-header">
                    <span class="vm-section-kicker">Transparent Field Card</span>
                    <h2 id="diagram-heading" class="vm-section-title">Every listing answers deal-breakers upfront</h2>
                </div>

                <div class="vm-diagram-grid">
                    <div class="vm-callout-col">
                        <div class="vm-callout-box">
                            <span class="vm-callout-num">1</span>
                            <div>
                                <h3 class="vm-callout-title">Exact commitment shape</h3>
                                <p class="vm-callout-desc">IN PERSON stamp: shifts never get mixed up with donation wishlists.</p>
                            </div>
                        </div>

                        <div class="vm-callout-box">
                            <span class="vm-callout-num">2</span>
                            <div>
                                <h3 class="vm-callout-title">Fit rules upfront</h3>
                                <p class="vm-callout-desc">Good for groups, Ages 12+, accessibility tags shown clearly.</p>
                            </div>
                        </div>
                    </div>

                    @if(featuredProject != null)
                        <article class="vm-listing-card" aria-label="Featured volunteer opportunity">
                            <div class="vm-listing-header">
                                <span class="vm-type-stamp">${featuredProject.projectTypeLabel()}</span>
                                @if(featuredProject.verified())
                                    <span class="vm-verified-badge">
                                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                                            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path>
                                            <path d="m9 12 2 2 4-4"></path>
                                        </svg>
                                        <span>Verified</span>
                                    </span>
                                @endif
                            </div>

                            <p class="vm-listing-org">${featuredProject.organizationName()}</p>
                            <h3 class="vm-listing-title">${featuredProject.title()}</h3>

                            <div class="vm-listing-meta">
                                <span class="vm-listing-meta-item">
                                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                                        <path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z"></path>
                                        <circle cx="12" cy="10" r="3"></circle>
                                    </svg>
                                    <span>${featuredProject.locationLabel() != null ? featuredProject.locationLabel() : "Local partner site"}</span>
                                </span>
                            </div>

                            <p class="vm-listing-desc">${featuredProject.description()}</p>

                            <div class="vm-listing-tags">
                                <span class="vm-tag-chip">Good for groups</span>
                                <span class="vm-tag-chip">Ages 12+</span>
                                <span class="vm-tag-chip">Step-free access</span>
                            </div>

                            <div class="vm-listing-footer">
                                <span class="vm-capacity-stamp">Real-time capacity</span>
                                <a href="/projects" class="vm-btn vm-btn-primary">
                                    <span>View in Directory</span>
                                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                                        <path d="M5 12h14"></path>
                                        <path d="m12 5 7 7-7 7"></path>
                                    </svg>
                                </a>
                            </div>
                        </article>
                    @else
                        <article class="vm-listing-card" aria-label="Volunteer directory status">
                            <div class="vm-listing-header">
                                <span class="vm-type-stamp">OPEN DIRECTORY</span>
                                <span class="vm-verified-badge">
                                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                                        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path>
                                        <path d="m9 12 2 2 4-4"></path>
                                    </svg>
                                    <span>Human-Reviewed</span>
                                </span>
                            </div>

                            <h3 class="vm-listing-title">No featured listing is active right now</h3>
                            <p class="vm-listing-desc">
                                Browse all live community opportunities in the directory, or post a new shift or supply drive for your organization.
                            </p>

                            <div class="vm-listing-footer">
                                <a href="/host" class="vm-btn vm-btn-outline">Post an Opportunity</a>
                                <a href="/projects" class="vm-btn vm-btn-primary">
                                    <span>Browse Directory</span>
                                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                                        <path d="M5 12h14"></path>
                                        <path d="m12 5 7 7-7 7"></path>
                                    </svg>
                                </a>
                            </div>
                        </article>
                    @endif

                    <div class="vm-callout-col">
                        <div class="vm-callout-box">
                            <span class="vm-callout-num">3</span>
                            <div>
                                <h3 class="vm-callout-title">Human-reviewed orgs</h3>
                                <p class="vm-callout-desc">Verified badge: checked against safety guidelines, no paid placement.</p>
                            </div>
                        </div>

                        <div class="vm-callout-box">
                            <span class="vm-callout-num">4</span>
                            <div>
                                <h3 class="vm-callout-title">Live remaining spots</h3>
                                <p class="vm-callout-desc">Real-time capacity so you never arrive at a full site.</p>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </section>

        <section class="vm-section" aria-labelledby="steps-heading">
            <div class="vm-container">
                <div class="vm-section-header">
                    <span class="vm-section-kicker">How It Works</span>
                    <h2 id="steps-heading" class="vm-section-title">Zero-friction sign-up</h2>
                </div>

                <div class="vm-steps-grid">
                    <div class="vm-step-node">
                        <div class="vm-step-top">
                            <span class="vm-step-num">1</span>
                            <h3 class="vm-step-title">Filter openly</h3>
                        </div>
                        <p class="vm-step-desc">Search by location, schedule, skill, and participation type without creating an account first.</p>
                    </div>

                    <div class="vm-step-node">
                        <div class="vm-step-top">
                            <span class="vm-step-num">2</span>
                            <h3 class="vm-step-title">Claim with email</h3>
                        </div>
                        <p class="vm-step-desc">Reserve an open shift or drop-off window in seconds and receive clear arrival details.</p>
                    </div>

                    <div class="vm-step-node">
                        <div class="vm-step-top">
                            <span class="vm-step-num">3</span>
                            <h3 class="vm-step-title">Show up ready</h3>
                        </div>
                        <p class="vm-step-desc">Arrive knowing parking, age rules, accessibility notes, and exact site contact details.</p>
                    </div>
                </div>

                <div class="vm-launchpad">
                    <div class="vm-launchpad-inner">
                        <div>
                            <h2 class="vm-launchpad-title">Ready to lend a hand?</h2>
                            <p class="vm-launchpad-sub">Search live shifts, supply drives, and remote tasks near you.</p>
                        </div>
                        <a href="/projects" class="vm-btn vm-btn-launchpad">
                            <span>Explore Opportunities</span>
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                                <path d="M5 12h14"></path>
                                <path d="m12 5 7 7-7 7"></path>
                            </svg>
                        </a>
                    </div>
                </div>
            </div>
        </section>
    </main>

    <footer class="vm-footer">
        <div class="vm-container vm-footer-inner">
            <span>Volunteer Monster: Community-first volunteer discovery</span>
            <span>Built for clear, honest civic service</span>
        </div>
    </footer>
</body>
</html>
