# Carleton Library Room Booking System

carleton university's library rooms are booked pretty regularly by students and get filled up super fast, which i didn't like. so i made an automated room booker using **java** and **selenium webdriver**, and made it into a **spring boot** microservice which runs every night on **docker** containers. books room 507 on a fixed weekly schedule.

## 📅 schedule

all bookings are made in **room 507**, 7 days ahead:

| day | time |
| --- | --- |
| monday | 8:30 - 10:30 AM |
| wednesday | 8:30 - 10:30 AM |
| thursday | 2:30 - 5:30 PM |
| friday | 2:30 - 5:30 PM |

runs on other days are skipped (nothing is scheduled for them).

## 🧪 tests

```bash
mvn test
```

slot matching, schedule lookup and grid parsing are unit-tested against fixed libcal payloads, so they run without a browser or network.

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
       -e BOOKING_USERNAME="your_username" \
       -e BOOKING_PASSWORD="your_password" \
       roombooking
    ```

### option 2: local java
1.  ensure you have java 17+ installed.
2.  run with maven:
    ```bash
    export BOOKING_USERNAME="your_username"
    export BOOKING_PASSWORD="your_password"
    ./mvnw spring-boot:run
    ```

## 📦 deployment (github actions)

this repo has a `.github/workflows/nightly_booking.yml` file.
1.  go to **settings > secrets and variables > actions**.
2.  add `BOOKING_USERNAME` and `BOOKING_PASSWORD`.
3.  the workflow runs at **05:01 UTC** (approx 12:01 AM EST) on mondays, wednesdays, thursdays and fridays to secure the room 7 days out.
