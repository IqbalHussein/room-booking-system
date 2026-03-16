# carleton library room booking system

carleton university's library rooms are booked pretty regularly by students and get filled up super fast, which i didn't like. so i made an automated room booker using **java** and **selenium webdriver**, and made it into a **spring boot** microservice which runs on **docker** containers via github actions. books a 3-hour slot on each weekday at a day-specific time:

| day | time slot |
|-----|-----------|
| monday, wednesday, friday | 2:30 – 5:30 PM |
| tuesday | 8:30 – 11:30 AM |
| thursday | 4:00 – 7:00 PM |
| saturday / sunday | no booking |

## 🚀 key features

*   **containerized execution**: the environment is fully dockerized with chrome and chromedriver.
*   **covers edge cases**: has algorithms to handle dynamic DOM changes and auto-retries across a 5-day lookahead window.
*   **CI/CD pipeline**: github actions for automated nightly execution (cron) and build verification

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
3.  the workflow runs every weekday at **midnight EDT** (`0 4 * * 1-5` UTC). the booking logic in `BookingService.java` selects the correct time slot based on the day of the week, and skips weekends automatically.
