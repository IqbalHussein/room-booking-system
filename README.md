# Distributed Room Reservation System

A cloud-native, microservice-based architecture for automating university resource allocation. Built with **Java**, **Spring Boot**, **Selenium WebDriver**, and **Docker**.

## 🚀 Key Features

*   **Microservice Architecture**: RESTful API-driven design using Spring Boot.
*   **Containerized Execution**: Fully Dockerized environment including Chrome & ChromeDriver for "write once, run anywhere" deployment.
*   **Self-Healing Logic**: Heuristic algorithms to handle dynamic DOM changes and auto-retry across a 5-day lookahead window.
*   **CI/CD Pipeline**: GitHub Actions workflow for automated nightly execution (Cron) and build verification.
*   **Secure**: Uses Environment Variables for sensitive credential injection.

## 🛠 Tech Stack

*   **Language**: Java 17
*   **Framework**: Spring Boot 3.2
*   **Automation**: Selenium WebDriver 4.16
*   **Infrastructure**: Docker, GitHub Actions (CI/CD)

## 🏃‍♂️ How to Run

### Option 1: Docker (Recommended)
This method requires no local Java or Chrome setup.

1.  **Build the Image**:
    ```bash
    docker build -t roombooking .
    ```

2.  **Run the Container**:
    ```bash
    docker run -p 8080:8080 \
       -e BOOKING_USERNAME="your_username" \
       -e BOOKING_PASSWORD="your_password" \
       roombooking
    ```
    *The system will start, perform the booking automatically via the API trigger mechanism, and log the result.*

### Option 2: Local Java
1.  Ensure you have Java 17+ installed.
2.  Run with Maven:
    ```bash
    export BOOKING_USERNAME="your_username"
    export BOOKING_PASSWORD="your_password"
    ./mvnw spring-boot:run
    ```

## ⚙️ API Endpoints

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/api/v1/book` | Triggers the room booking automation logic. Returns success message or error log. |

## 📦 Deployment (GitHub Actions)

This repository includes a `.github/workflows/nightly_booking.yml` file.
1.  Go to **Settings > Secrets and variables > Actions**.
2.  Add `BOOKING_USERNAME` and `BOOKING_PASSWORD`.
3.  The workflow will automatically run every night at **12:01 AM UTC** to secure a room.
