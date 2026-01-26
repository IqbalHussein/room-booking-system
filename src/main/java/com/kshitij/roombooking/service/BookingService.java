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

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class BookingService {

    private static final Logger logger = LoggerFactory.getLogger(BookingService.class);
    private static final int REQUIRED_DURATION_MINUTES = 180; // 3 hours

    public String bookRoom() throws Exception {
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
            LocalDate today = LocalDate.now();
            LocalDate targetDate = today.plusDays(7);

            String bookedSlotInfo = "";
            boolean slotFound = false;

            // Get room order (324A first)
            List<String> roomOrder = getRoomOrder(driver, wait, js);

            // ========== PHASE 1: Search ALL rooms for 11:30 slot with 3-hour availability
            // ==========
            logger.info("PHASE 1: Searching for 11:30 slot with 3-hour availability...");

            for (String roomName : roomOrder) {
                if (slotFound)
                    break;

                logger.info("Checking room for 11:30 (3hr): {}", roomName);

                navigateToRoom(driver, wait, js, roomName);
                navigateToDate(driver, wait, today, targetDate);

                // Look for 11:30 slot
                List<WebElement> availableSlots = driver.findElements(By.className("s-lc-eq-avail"));
                for (WebElement slot : availableSlots) {
                    String label = slot.getAttribute("aria-label");
                    if (label != null && label.contains("11:30")) {
                        logger.info("Found 11:30 slot, checking if 3hr available...");

                        js.executeScript(
                                "arguments[0].scrollIntoView({behavior: 'auto', block: 'center', inline: 'center'});",
                                slot);
                        wait.until(ExpectedConditions.elementToBeClickable(slot));
                        slot.click();
                        Thread.sleep(1000);

                        // Check if 3-hour duration is available (11:30 + 3hr = 2:30pm)
                        if (has3HourDuration(driver, wait, "11:30")) {
                            bookedSlotInfo = label;
                            logger.info("3-hour option available! Booking: {}", bookedSlotInfo);
                            select3HourDuration(driver, wait, "11:30");
                            slotFound = true;
                            break;
                        } else {
                            logger.info("3-hour not available in {}. Trying next room...", roomName);
                            break; // Exit slot loop, try next room
                        }
                    }
                }
            }

            // ========== PHASE 2: If no 11:30 with 3hr, find next slot after 11:30 with 3hr
            // ==========
            if (!slotFound) {
                logger.info("PHASE 2: No 11:30 with 3hr. Looking for next available after 11:30 with 3hr...");

                for (String roomName : roomOrder) {
                    if (slotFound)
                        break;

                    logger.info("Checking room for post-11:30 (3hr): {}", roomName);

                    navigateToRoom(driver, wait, js, roomName);
                    navigateToDate(driver, wait, today, targetDate);

                    // Get fresh slot list and find slots after 11:30
                    List<WebElement> availableSlots = driver.findElements(By.className("s-lc-eq-avail"));

                    // Build list of slot times after 11:30
                    List<String> slotTimes = new ArrayList<>();
                    for (WebElement slot : availableSlots) {
                        String label = slot.getAttribute("aria-label");
                        if (label != null) {
                            String time = extractTime(label);
                            if (time != null && convertToMinutes(time) > 690) { // After 11:30
                                slotTimes.add(time);
                            }
                        }
                    }

                    // Sort by time
                    slotTimes.sort((a, b) -> convertToMinutes(a) - convertToMinutes(b));

                    // Try each slot time
                    for (String slotTime : slotTimes) {
                        if (slotFound)
                            break;

                        // Re-find the slot element (fresh reference)
                        navigateToRoom(driver, wait, js, roomName);
                        navigateToDate(driver, wait, today, targetDate);

                        List<WebElement> freshSlots = driver.findElements(By.className("s-lc-eq-avail"));
                        for (WebElement slot : freshSlots) {
                            String label = slot.getAttribute("aria-label");
                            if (label != null
                                    && label.toLowerCase().contains(slotTime.replace("am", "").replace("pm", ""))) {
                                logger.info("Trying slot: {}", label);

                                js.executeScript(
                                        "arguments[0].scrollIntoView({behavior: 'auto', block: 'center', inline: 'center'});",
                                        slot);
                                wait.until(ExpectedConditions.elementToBeClickable(slot));
                                slot.click();
                                Thread.sleep(1000);

                                if (has3HourDuration(driver, wait, slotTime)) {
                                    bookedSlotInfo = label;
                                    logger.info("3-hour option available! Booking: {}", bookedSlotInfo);
                                    select3HourDuration(driver, wait, slotTime);
                                    slotFound = true;
                                } else {
                                    logger.info("3-hour not available for {}. Trying next slot...", slotTime);
                                }
                                break; // Found the slot element, move on
                            }
                        }
                    }
                }
            }

            if (!slotFound) {
                throw new Exception("No 3-hour slots found in any room.");
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

            return "Success! Booked: " + bookedSlotInfo;

        } catch (Exception e) {
            logger.error("Booking process failed", e);
            throw e;
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }

    private boolean has3HourDuration(WebDriver driver, WebDriverWait wait, String startTime) {
        try {
            WebElement dropdownElement = wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("select")));
            Select dropdown = new Select(dropdownElement);
            List<WebElement> options = dropdown.getOptions();

            int startMins = convertToMinutes(startTime);
            int targetEndMins = startMins + REQUIRED_DURATION_MINUTES; // 3 hours later

            logger.info("Start time: {} ({}min), need end time at {}min or later", startTime, startMins, targetEndMins);

            for (WebElement option : options) {
                String optionText = option.getText().toLowerCase();
                String endTime = extractTime(optionText);
                if (endTime != null) {
                    int endMins = convertToMinutes(endTime);
                    logger.info("Option: {} -> end time {} ({}min)", optionText, endTime, endMins);
                    if (endMins >= targetEndMins) {
                        logger.info("Found 3hr+ option: {}", option.getText());
                        return true;
                    }
                }
            }

            return false;
        } catch (Exception e) {
            logger.warn("Error checking duration options: {}", e.getMessage());
            return false;
        }
    }

    private void select3HourDuration(WebDriver driver, WebDriverWait wait, String startTime)
            throws InterruptedException {
        WebElement dropdownElement = wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("select")));
        Select dropdown = new Select(dropdownElement);
        List<WebElement> options = dropdown.getOptions();

        int startMins = convertToMinutes(startTime);
        int targetEndMins = startMins + REQUIRED_DURATION_MINUTES;

        // Find the option that gives exactly 3 hours or closest to it
        int bestIndex = -1;
        int bestDiff = Integer.MAX_VALUE;

        for (int i = 0; i < options.size(); i++) {
            String optionText = options.get(i).getText().toLowerCase();
            String endTime = extractTime(optionText);
            if (endTime != null) {
                int endMins = convertToMinutes(endTime);
                if (endMins >= targetEndMins) {
                    int diff = endMins - targetEndMins;
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        bestIndex = i;
                    }
                }
            }
        }

        if (bestIndex >= 0) {
            dropdown.selectByIndex(bestIndex);
            logger.info("Selected duration: {}", options.get(bestIndex).getText());
        } else {
            // Fallback to last option
            dropdown.selectByIndex(options.size() - 1);
            logger.info("Selected max duration: {}", options.get(options.size() - 1).getText());
        }
        Thread.sleep(1000);
    }

    private List<String> getRoomOrder(WebDriver driver, WebDriverWait wait, JavascriptExecutor js)
            throws InterruptedException {
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

        List<String> roomNames = new ArrayList<>();
        String room324A = null;

        for (WebElement room : roomLinks) {
            String name = room.getText();
            if (name.contains("324A")) {
                room324A = name;
            } else {
                roomNames.add(name);
            }
        }

        List<String> orderedRooms = new ArrayList<>();
        if (room324A != null) {
            orderedRooms.add(room324A);
        }
        orderedRooms.addAll(roomNames);

        logger.info("Room order: {}", orderedRooms);
        return orderedRooms;
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

    private String extractTime(String label) {
        if (label == null)
            return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2}:\\d{2}[ap]m)")
                .matcher(label.toLowerCase());
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private int convertToMinutes(String time) {
        if (time == null)
            return 0;
        time = time.toLowerCase();
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
