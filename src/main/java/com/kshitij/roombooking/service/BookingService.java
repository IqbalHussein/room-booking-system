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

            // Click "book study rooms"
            WebElement bookStudyRoomsLink = wait.until(ExpectedConditions.elementToBeClickable(
                    By.linkText("book study rooms on floors 2-5 in the library")));
            bookStudyRoomsLink.click();
            logger.info("Clicked on 'book study rooms' link.");

            // Scroll down to load rooms
            for (int i = 0; i < 5; i++) {
                js.executeScript("window.scrollBy(0, 1000);");
                Thread.sleep(1000);
            }

            // Get all room links and prioritize 324A
            List<WebElement> allRoomLinks = wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(
                    By.xpath("//a[contains(@href, '/space/')]")));

            if (allRoomLinks.isEmpty()) {
                throw new Exception("No rooms found on the page.");
            }

            // Reorder: 324A first, then others
            List<WebElement> orderedRooms = new ArrayList<>();
            WebElement room324A = null;
            for (WebElement room : allRoomLinks) {
                if (room.getText().contains("324A")) {
                    room324A = room;
                } else {
                    orderedRooms.add(room);
                }
            }
            if (room324A != null) {
                orderedRooms.add(0, room324A); // Put 324A first
            }

            LocalDate today = LocalDate.now();
            LocalDate oneWeekFromNow = today.plusDays(7);
            String bookedSlotInfo = "";
            boolean slotFound = false;

            // PHASE 1: Try to find 11:30 slot in any room (324A first)
            logger.info("PHASE 1: Searching for 11:30 slot across all rooms...");
            for (WebElement room : orderedRooms) {
                String roomName = room.getText();
                logger.info("Checking room: {}", roomName);

                js.executeScript("arguments[0].scrollIntoView({behavior: 'auto', block: 'center', inline: 'center'});",
                        room);
                wait.until(ExpectedConditions.elementToBeClickable(room));
                room.click();
                Thread.sleep(1000);

                // Navigate to target date
                navigateToDate(driver, wait, js, today, oneWeekFromNow);

                // Check for 11:30 slot
                List<WebElement> availableSlots = driver.findElements(By.className("s-lc-eq-avail"));
                for (WebElement slot : availableSlots) {
                    String label = slot.getAttribute("aria-label");
                    if (label != null && label.contains("11:30")) {
                        bookedSlotInfo = label;
                        logger.info("Found 11:30 slot in {}: {}", roomName, bookedSlotInfo);
                        js.executeScript(
                                "arguments[0].scrollIntoView({behavior: 'auto', block: 'center', inline: 'center'});",
                                slot);
                        wait.until(ExpectedConditions.elementToBeClickable(slot));
                        slot.click();
                        Thread.sleep(1000);
                        slotFound = true;
                        break;
                    }
                }

                if (slotFound)
                    break;

                // Go back to room list to try next room
                driver.navigate().back();
                Thread.sleep(1000);
                driver.navigate().back();
                Thread.sleep(1000);

                // Re-scroll to load rooms again
                for (int i = 0; i < 3; i++) {
                    js.executeScript("window.scrollBy(0, 1000);");
                    Thread.sleep(500);
                }

                // Refresh room list
                allRoomLinks = wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(
                        By.xpath("//a[contains(@href, '/space/')]")));
                orderedRooms = new ArrayList<>();
                room324A = null;
                for (WebElement r : allRoomLinks) {
                    if (r.getText().contains("324A")) {
                        room324A = r;
                    } else {
                        orderedRooms.add(r);
                    }
                }
                if (room324A != null) {
                    orderedRooms.add(0, room324A);
                }
            }

            // PHASE 2: If no 11:30 found, find next available slot after 11:30 in 324A (or
            // any room)
            if (!slotFound) {
                logger.info("PHASE 2: No 11:30 slot found. Looking for next available slot after 11:30...");

                for (WebElement room : orderedRooms) {
                    String roomName = room.getText();
                    logger.info("Checking room for post-11:30 slot: {}", roomName);

                    js.executeScript(
                            "arguments[0].scrollIntoView({behavior: 'auto', block: 'center', inline: 'center'});",
                            room);
                    wait.until(ExpectedConditions.elementToBeClickable(room));
                    room.click();
                    Thread.sleep(1000);

                    navigateToDate(driver, wait, js, today, oneWeekFromNow);

                    // Find best slot after 11:30
                    List<WebElement> availableSlots = driver.findElements(By.className("s-lc-eq-avail"));
                    WebElement bestSlot = null;
                    String bestTime = null;

                    for (WebElement slot : availableSlots) {
                        String label = slot.getAttribute("aria-label");
                        if (label != null) {
                            // Extract time from label (format like "11:30am" or "12:00pm")
                            String time = extractTime(label);
                            if (time != null && isAfter1130(time)) {
                                if (bestTime == null || compareTime(time, bestTime) < 0) {
                                    bestTime = time;
                                    bestSlot = slot;
                                }
                            }
                        }
                    }

                    if (bestSlot != null) {
                        bookedSlotInfo = bestSlot.getAttribute("aria-label");
                        logger.info("Found post-11:30 slot in {}: {}", roomName, bookedSlotInfo);
                        js.executeScript(
                                "arguments[0].scrollIntoView({behavior: 'auto', block: 'center', inline: 'center'});",
                                bestSlot);
                        wait.until(ExpectedConditions.elementToBeClickable(bestSlot));
                        bestSlot.click();
                        Thread.sleep(1000);
                        slotFound = true;
                        break;
                    }

                    // Go back to try next room
                    driver.navigate().back();
                    Thread.sleep(1000);
                    driver.navigate().back();
                    Thread.sleep(1000);

                    for (int i = 0; i < 3; i++) {
                        js.executeScript("window.scrollBy(0, 1000);");
                        Thread.sleep(500);
                    }

                    allRoomLinks = wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(
                            By.xpath("//a[contains(@href, '/space/')]")));
                    orderedRooms = new ArrayList<>();
                    room324A = null;
                    for (WebElement r : allRoomLinks) {
                        if (r.getText().contains("324A")) {
                            room324A = r;
                        } else {
                            orderedRooms.add(r);
                        }
                    }
                    if (room324A != null) {
                        orderedRooms.add(0, room324A);
                    }
                }
            }

            if (!slotFound) {
                throw new Exception("No available time slots found in any room.");
            }

            // Duration Dropdown - select maximum
            WebElement dropdownElement = wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("select")));
            Select dropdown = new Select(dropdownElement);
            dropdown.selectByIndex(dropdown.getOptions().size() - 1);
            Thread.sleep(1000);

            // Submit Times
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

    private void navigateToDate(WebDriver driver, WebDriverWait wait, JavascriptExecutor js,
            LocalDate today, LocalDate targetDate) throws InterruptedException {
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
        // Extract time like "11:30am" or "12:00pm" from aria-label
        if (label == null)
            return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2}:\\d{2}[ap]m)")
                .matcher(label.toLowerCase());
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private boolean isAfter1130(String time) {
        // Convert to 24h and check if >= 11:30
        int mins = convertToMinutes(time);
        return mins >= 11 * 60 + 30; // 11:30 = 690 minutes
    }

    private int compareTime(String time1, String time2) {
        return convertToMinutes(time1) - convertToMinutes(time2);
    }

    private int convertToMinutes(String time) {
        // Convert "11:30am" or "1:00pm" to minutes since midnight
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
