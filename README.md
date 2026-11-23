# Carleton Library Room Booking System

carleton university's library rooms are booked pretty regularly by students and get filled up super fast, which i didn't like. so i made an automated room booker using **java** and **selenium webdriver**, and made it into a **spring boot** microservice which runs every night on **docker** containers. each person picks their own rooms and times in a small web app, and the booker grabs them every night.

## 📅 schedule (no more hardcoding)

rooms and times are set in the **scheduler web app**: https://room-booking-scheduler.vercel.app

*   **profiles**: each person gets their own profile, opened with a 4-digit passcode. a profile holds that person's carleton login, so every booking is made under their own account.
*   **weekly editor**: a week-at-a-glance timeline; tap a day to switch it on, then set one or more **time blocks** (e.g. two 90-minute sessions) in 30-minute steps. each block has up to 5 rooms **in priority order**: if the first is taken, the booker tries the next.
*   **3-hour daily limit**: libcal allows one account 3h per day, so blocks are capped at 3h combined (enforced in the editor, the api and the booker).
*   **coming up**: shows what has been booked for the next week and what tonight's run will try.
*   **conflict warnings**: once a room is booked, no one else can book it. if another profile wants the same room at an overlapping time, the editor flags it (and says whose booking runs first).
*   **results**: each night's outcome (booked / taken / failed) shows up on the profile.
*   **security**: profile records are AES-256-GCM encrypted in a private vercel blob store. 5 wrong passcodes lock the profile for 15 minutes (doubling each time). new profiles need an invite code.

the nightly job books 7 days ahead for every profile, one after another in the order the profiles were created; each block is a separate libcal booking. a room booked by an earlier profile in the same run is skipped for later ones, which go straight to their backup rooms.

if no scheduler is configured, the service falls back to `src/main/resources/default-schedule.json` (room 507, mon/wed 8:30-10:30am, thu/fri 2:30-5:30pm) with `BOOKING_USERNAME` / `BOOKING_PASSWORD`.

## 🧪 tests

```bash
mvn test
```

slot matching, schedule parsing, room-name matching and grid parsing are unit-tested against fixed libcal payloads, so they run without a browser or network.

## 🚀 key features

*   **containerized execution**: the environment is fully dockerized with chrome and chromedriver.
*   **reads real slot times**: libcal renders its grid with fullcalendar, where cells carry no readable time at all. the booker reads the same `/spaces/availability/grid` json the page itself fetches, so slot times are exact rather than scraped.
*   **honest failure reporting**: every outcome is tagged `SUCCESS` / `SKIPPED` / `UNAVAILABLE` / `FAILED`. a room that is simply already booked is *not* reported as a failure, so a red build always means something genuinely broke.
*   **staged errors**: failures name the step that broke (`ROOM_LOOKUP`, `GRID_FETCH`, `GRID_MISMATCH`, `END_TIME`, `LOGIN`, ...) and include what was actually seen.
*   **markup-change tripwire**: if the json and the rendered grid disagree on how many slots are free, the booker refuses to click rather than risk booking the wrong slot.
*   **CI/CD pipeline**: github actions for automated nightly execution (cron), unit tests, and build verification

## 🏃‍♂️ how to run

### option 1: docker (recommended)
this method requires no local java or chrome setup

1.  **build the image**:
    ```bash
    docker build -t roombooking .
    ```

2.  **run the container**:
    ```bash
    docker run -p 8080:8080 \
       -e BOOKING_CONFIG_URL="https://room-booking-scheduler.vercel.app" \
       -e BOOKING_SYNC_TOKEN="your_sync_token" \
       roombooking
    ```
    (or pass `BOOKING_USERNAME` / `BOOKING_PASSWORD` instead to use the bundled default schedule.)

3.  **trigger a run**:
    ```bash
    curl -X POST localhost:8080/api/v1/book
    ```

### option 2: local java
1.  ensure you have java 17+ installed.
2.  run with maven:
    ```bash
    export BOOKING_USERNAME="your_username"
    export BOOKING_PASSWORD="your_password"
    ./mvnw spring-boot:run
    ```

## 📦 deployment

### scheduler web app (`web/`, vercel)
static ui + node serverless functions + vercel blob storage. no build step.

| env var (vercel) | purpose |
| --- | --- |
| `BLOB_READ_WRITE_TOKEN` | added automatically when the blob store is connected |
| `ENCRYPTION_KEY` | encrypts profile records and signs sessions. changing it makes existing profiles unreadable |
| `SYNC_TOKEN` | shared secret the nightly job uses for `/api/export` and `/api/results` |
| `INVITE_CODE` | required to create a profile |

```bash
cd web && npx vercel deploy --prod
```

api: `GET/POST /api/profiles`, `POST /api/session`, `GET/PUT/DELETE /api/profile`, `GET /api/occupancy`, and (sync token only) `GET /api/export`, `POST /api/results`.

### nightly booking (github actions)
`.github/workflows/nightly_booking.yml` runs **every day at 05:01 UTC** (approx 12:01 AM EST). days with nothing scheduled are reported as `SKIPPED`.
1.  go to **settings > secrets and variables > actions**.
2.  add the secret `BOOKING_SYNC_TOKEN` (same value as the vercel `SYNC_TOKEN`).
3.  optional: set the variable `BOOKING_CONFIG_URL` if the scheduler moves (defaults to the vercel url above).

the job fails only when a profile genuinely `FAILED`; a taken room is a notice, not an error.
