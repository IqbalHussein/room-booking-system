package com.kshitij.roombooking.service;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class BookingService {

    private static final Logger logger = LoggerFactory.getLogger(BookingService.class);

    /** Every booking goes in this room. */
    private static final String TARGET_ROOM = "507";

    /** Weekly schedule: Mon/Wed 8:30-10:30am, Thu/Fri 2:30-5:30pm. */
    private static final Map<DayOfWeek, BookingWindow> SCHEDULE = Map.of(
            DayOfWeek.MONDAY, new BookingWindow("8:30am", "10:30am"),
            DayOfWeek.WEDNESDAY, new BookingWindow("8:30am", "10:30am"),
            DayOfWeek.THURSDAY, new BookingWindow("2:30pm", "5:30pm"),
            DayOfWeek.FRIDAY, new BookingWindow("2:30pm", "5:30pm"));

    private record BookingWindow(String startTime, String endTime) {
        int durationMinutes() {
            return convertToMinutes(endTime) - convertToMinutes(startTime);
        }
    }

    public String bookRoom() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate targetDate = today.plusDays(7);

        BookingWindow window = SCHEDULE.get(targetDate.getDayOfWeek());
        if (window == null) {
            String message = "Skipped: nothing scheduled for " + targetDate.getDayOfWeek() + " (" + targetDate + ").";
            logger.info(message);
            return message;
        }

        logger.info("Target: room {} on {} ({}) from {} to {} ({} minutes).",
                TARGET_ROOM, targetDate, targetDate.getDayOfWeek(),
                window.startTime(), window.endTime(), window.durationMinutes());

        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--window-size=1920,1080");

        WebDriver driver = new ChromeDriver(options);

        try {
            logger.info("Navigating to LibCal homepage.");
            driver.get("https://carletonu.libcal.com/");
            driver.manage().window().maximize();

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
            JavascriptExecutor js = (JavascriptExecutor) driver;

            String roomName = findRoom(driver, wait, js, TARGET_ROOM);
            navigateToRoom(driver, wait, js, roomName);
            navigateToDate(driver, wait, today, targetDate);

            String bookedSlotInfo = selectSlot(driver, wait, js, window);
            if (bookedSlotInfo == null) {
                throw new Exception("Room " + TARGET_ROOM + " has no " + window.startTime() + "-" + window.endTime()
                        + " availability on " + targetDate + ".");
            }

            // Submit Times
            Thread.sleep(1000);
            WebElement submitButton = wait.until(ExpectedConditions.presenceOfElementLocated(By.id("submit_times")));
            js.executeScript("arguments[0].scrollIntoView({behavior: 'auto', block: 'center', inline: 'center'});",
                    submitButton);
            Thread.sleep(500);
            wait.until(ExpectedConditions.elementToBeClickable(submitButton));
            submitButton.click();
            Thread.sleep(2000);

            // Login
            String username = System.getenv("BOOKING_USERNAME");
            String password = System.getenv("BOOKING_PASSWORD");

            if (username == null || password == null) {
                throw new Exception("Environment variables BOOKING_USERNAME or BOOKING_PASSWORD are not set!");
            }

            WebElement usernameField = wait.until(ExpectedConditions.presenceOfElementLocated(By.id("userNameInput")));
            usernameField.sendKeys(username);

            WebElement passwordField = wait.until(ExpectedConditions.presenceOfElementLocated(By.id("passwordInput")));
            passwordField.sendKeys(password);

            WebElement signInButton = wait.until(ExpectedConditions.elementToBeClickable(By.id("submitButton")));
            signInButton.click();
            Thread.sleep(2000);

            // Continue Button
            WebElement continueButton = wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.xpath("//button[contains(text(), 'Continue')]")));
            js.executeScript("arguments[0].scrollIntoView({behavior: 'auto', block: 'center', inline: 'center'});",
                    continueButton);
            Thread.sleep(500);
            wait.until(ExpectedConditions.elementToBeClickable(continueButton)).click();
            logger.info("Clicked on 'Continue' button.");

            // Final Submit
            WebElement submitBookingButton = wait.until(ExpectedConditions.elementToBeClickable(
                    By.xpath("//button[contains(text(), 'Submit my Booking')]")));
            submitBookingButton.click();
            logger.info("Booking submitted successfully!");

            return "Success! Booked " + roomName + " on " + targetDate + " " + window.startTime() + "-"
                    + window.endTime() + " (" + bookedSlotInfo + ")";

        } catch (Exception e) {
            logger.error("Booking process failed", e);
            throw e;
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }

    /**
     * Clicks the slot starting at the scheduled start time and picks the end time
     * that closes the window exactly. Returns the slot label, or null if either the
     * slot or the matching end time is unavailable.
     */
    private String selectSlot(WebDriver driver, WebDriverWait wait, JavascriptExecutor js, BookingWindow window)
            throws InterruptedException {
        List<WebElement> availableSlots = driver.findElements(By.className("s-lc-eq-avail"));

        for (WebElement slot : availableSlots) {
            String label = slot.getAttribute("aria-label");
            if (!window.startTime().equals(extractTime(label))) {
                continue;
            }

            logger.info("Found {} slot, checking for a {}-minute duration...", window.startTime(),
                    window.durationMinutes());

            js.executeScript("arguments[0].scrollIntoView({behavior: 'auto', block: 'center', inline: 'center'});",
                    slot);
            wait.until(ExpectedConditions.elementToBeClickable(slot));
            slot.click();
            Thread.sleep(1000);

            if (selectEndTime(wait, window)) {
                logger.info("Booking slot: {}", label);
                return label;
            }

            logger.info("No end time at {} offered for the {} slot.", window.endTime(), window.startTime());
            return null;
        }

        logger.info("No available {} slot in room {}.", window.startTime(), TARGET_ROOM);
        return null;
    }

    /** Selects the duration option that ends exactly at the end of the window. */
    private boolean selectEndTime(WebDriverWait wait, BookingWindow window) throws InterruptedException {
        try {
            WebElement dropdownElement = wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("select")));
            Select dropdown = new Select(dropdownElement);
            List<WebElement> options = dropdown.getOptions();

            int targetEndMins = convertToMinutes(window.endTime());

            for (int i = 0; i < options.size(); i++) {
                String optionText = options.get(i).getText();
                String endTime = extractTime(optionText);
                if (endTime != null && convertToMinutes(endTime) == targetEndMins) {
                    dropdown.selectByIndex(i);
                    logger.info("Selected end time: {}", optionText);
                    Thread.sleep(1000);
                    return true;
                }
                logger.info("End time option (no match): {}", optionText);
            }

            return false;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("Error reading duration options: {}", e.getMessage());
            return false;
        }
    }

    /** Resolves the full LibCal name of the room whose label contains roomNumber. */
    private String findRoom(WebDriver driver, WebDriverWait wait, JavascriptExecutor js, String roomNumber)
            throws Exception {
        WebElement bookStudyRoomsLink = wait.until(ExpectedConditions.elementToBeClickable(
                By.linkText("book study rooms on floors 2-5 in the library")));
        bookStudyRoomsLink.click();
        logger.info("Clicked on 'book study rooms' link.");

        for (int i = 0; i < 5; i++) {
            js.executeScript("window.scrollBy(0, 1000);");
            Thread.sleep(1000);
        }

        List<WebElement> roomLinks = wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(
                By.xpath("//a[contains(@href, '/space/')]")));

        List<String> seen = new ArrayList<>();
        for (WebElement room : roomLinks) {
            String name = room.getText();
            seen.add(name);
            if (name.contains(roomNumber)) {
                logger.info("Resolved room {} to: {}", roomNumber, name);
                return name;
            }
        }

        throw new Exception("Room " + roomNumber + " was not listed. Rooms found: " + seen);
    }

    private void navigateToRoom(WebDriver driver, WebDriverWait wait, JavascriptExecutor js, String roomName)
            throws InterruptedException {
        driver.get("https://carletonu.libcal.com/");
        Thread.sleep(1000);

        WebElement bookStudyRoomsLink = wait.until(ExpectedConditions.elementToBeClickable(
                By.linkText("book study rooms on floors 2-5 in the library")));
        bookStudyRoomsLink.click();
        Thread.sleep(1000);

        for (int i = 0; i < 5; i++) {
            js.executeScript("window.scrollBy(0, 1000);");
            Thread.sleep(1000);
        }

        List<WebElement> roomLinks = wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(
                By.xpath("//a[contains(@href, '/space/')]")));

        for (WebElement room : roomLinks) {
            if (room.getText().equals(roomName)) {
                js.executeScript("arguments[0].scrollIntoView({behavior: 'auto', block: 'center', inline: 'center'});",
                        room);
                wait.until(ExpectedConditions.elementToBeClickable(room));
                room.click();
                Thread.sleep(1000);
                break;
            }
        }
    }

    private void navigateToDate(WebDriver driver, WebDriverWait wait, LocalDate today, LocalDate targetDate)
            throws InterruptedException {
        WebElement goToDateButton = wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//button[contains(text(), 'Go To Date')]")));
        goToDateButton.click();
        Thread.sleep(1000);

        if (targetDate.getMonth() != today.getMonth()) {
            logger.info("Target date is in next month. Switching calendar...");
            WebElement nextMonthBtn = wait.until(ExpectedConditions.elementToBeClickable(
                    By.cssSelector("div.datepicker-days th.next")));
            nextMonthBtn.click();
            Thread.sleep(500);
        }

        String dayText = String.valueOf(targetDate.getDayOfMonth());
        List<WebElement> allDates = wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(
                By.xpath("//td[contains(@class, 'day') and not(contains(@class, 'old'))]")));

        for (WebElement date : allDates) {
            if (date.getText().equals(dayText)) {
                date.click();
                break;
            }
        }
        Thread.sleep(1000);
    }

    private static String extractTime(String label) {
        if (label == null)
            return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2}:\\d{2}\\s*[ap]m)")
                .matcher(label.toLowerCase());
        if (m.find()) {
            return m.group(1).replaceAll("\\s+", "");
        }
        return null;
    }

    private static int convertToMinutes(String time) {
        if (time == null)
            return 0;
        time = time.toLowerCase().replaceAll("\\s+", "");
        boolean isPM = time.contains("pm");
        time = time.replace("am", "").replace("pm", "");
        String[] parts = time.split(":");
        int hours = Integer.parseInt(parts[0]);
        int mins = Integer.parseInt(parts[1]);

        if (isPM && hours != 12)
            hours += 12;
        if (!isPM && hours == 12)
            hours = 0;

        return hours * 60 + mins;
    }
}
