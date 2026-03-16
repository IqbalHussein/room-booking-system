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
import java.time.DayOfWeek;
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

            // Determine target start time based on day of week; skip weekends
            DayOfWeek targetDay = targetDate.getDayOfWeek();
            // preferredStartTime always includes am/pm so matching and minute-conversion are unambiguous
            String preferredStartTime;
            if (targetDay == DayOfWeek.SATURDAY || targetDay == DayOfWeek.SUNDAY) {
                logger.info("Target date {} is a weekend ({}). Skipping booking.", targetDate, targetDay);
                return "Skipped: No booking on weekends.";
            } else if (targetDay == DayOfWeek.TUESDAY) {
                preferredStartTime = "8:30am";   // 8:30 – 11:30 AM
            } else if (targetDay == DayOfWeek.THURSDAY) {
                preferredStartTime = "4:00pm";   // 4:00 – 7:00 PM
            } else {
                // Monday, Wednesday, Friday
                preferredStartTime = "2:30pm";   // 2:30 – 5:30 PM
            }
            logger.info("Target date {} is a {} — preferred start: {}", targetDate, targetDay, preferredStartTime);

            String bookedSlotInfo = "";
            boolean slotFound = false;

            // Get room order (324B first)
            List<String> roomOrder = getRoomOrder(driver, wait, js);

            // ========== PHASE 1: Search ALL rooms for preferred slot with 3-hour availability ==========
            logger.info("PHASE 1: Searching for {} slot with 3-hour availability...", preferredStartTime);

            for (String roomName : roomOrder) {
                if (slotFound)
                    break;

                logger.info("Checking room for {} (3hr): {}", preferredStartTime, roomName);

                navigateToRoom(driver, wait, js, roomName);
                navigateToDate(driver, wait, today, targetDate);

                // Look for preferred start time slot — use extractTime() for exact match,
                // avoiding substring false-positives (e.g. "2:30pm" inside "12:30pm").
                List<WebElement> availableSlots = driver.findElements(By.className("s-lc-eq-avail"));
                for (WebElement slot : availableSlots) {
                    String label = slot.getAttribute("aria-label");
                    if (label != null && preferredStartTime.equals(extractTime(label))) {
                        logger.info("Found {} slot in {}, checking if 3hr available...", preferredStartTime, roomName);

                        js.executeScript(
                                "arguments[0].scrollIntoView({behavior: 'auto', block: 'center', inline: 'center'});",
                                slot);
                        wait.until(ExpectedConditions.elementToBeClickable(slot));
                        slot.click();
                        Thread.sleep(1000);

                        // Check if 3-hour duration is available
                        if (has3HourDuration(driver, wait, preferredStartTime)) {
                            bookedSlotInfo = label;
                            logger.info("3-hour option available! Booking: {}", bookedSlotInfo);
                            select3HourDuration(driver, wait, preferredStartTime);
                            slotFound = true;
                            break;
                        } else {
                            logger.info("3-hour not available in {}. Trying next room...", roomName);
                            break; // Exit slot loop, try next room
                        }
                    }
                }
            }

            // ========== PHASE 2: TIME-FIRST fallback — collect all (slotTime, room) pairs
            //            across every room, sort by time proximity, then attempt each in order.
            //            This prioritises getting the right time window over getting a preferred room.
            // ==========
            if (!slotFound) {
                logger.info("PHASE 2: No {} with 3hr. Scanning all rooms for earliest available slot at or after {}...",
                        preferredStartTime, preferredStartTime);

                int preferredStartMins = convertToMinutes(preferredStartTime);

                // Collect candidate (slotTime, roomName) pairs from all rooms in one pass
                // Each entry: [0] = slotTime string (with am/pm), [1] = roomName
                List<String[]> candidates = new ArrayList<>();

                for (String roomName : roomOrder) {
                    logger.info("Scanning room for fallback slots: {}", roomName);
                    navigateToRoom(driver, wait, js, roomName);
                    navigateToDate(driver, wait, today, targetDate);

                    List<WebElement> availableSlots = driver.findElements(By.className("s-lc-eq-avail"));
                    for (WebElement slot : availableSlots) {
                        String label = slot.getAttribute("aria-label");
                        if (label != null) {
                            String time = extractTime(label);
                            if (time != null && convertToMinutes(time) >= preferredStartMins) {
                                candidates.add(new String[]{time, roomName});
                                logger.info("Candidate: {} in {}", time, roomName);
                            }
                        }
                    }
                }

                // Sort candidates by time ascending (closest to preferred time first)
                candidates.sort((a, b) -> convertToMinutes(a[0]) - convertToMinutes(b[0]));

                // Try each candidate time+room until a 3-hour slot is found
                for (String[] candidate : candidates) {
                    if (slotFound)
                        break;

                    String slotTime = candidate[0];
                    String roomName = candidate[1];
                    logger.info("Trying fallback slot {} in room {}", slotTime, roomName);

                    navigateToRoom(driver, wait, js, roomName);
                    navigateToDate(driver, wait, today, targetDate);

                    List<WebElement> freshSlots = driver.findElements(By.className("s-lc-eq-avail"));
                    for (WebElement slot : freshSlots) {
                        String label = slot.getAttribute("aria-label");
                        if (label != null && slotTime.equals(extractTime(label))) {
                            logger.info("Clicking slot: {}", label);

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
                                logger.info("3-hour not available for {} in {}. Moving to next candidate...", slotTime, roomName);
                            }
                            break; // Move to next candidate regardless
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
        String room324B = null;

        for (WebElement room : roomLinks) {
            String name = room.getText();
            if (name.contains("324B")) {
                room324B = name; // Prioritise 324B
            } else {
                roomNames.add(name);
            }
        }

        List<String> orderedRooms = new ArrayList<>();
        if (room324B != null) {
            orderedRooms.add(room324B);
        }
        orderedRooms.addAll(roomNames);

        logger.info("Room order (324B first): {}", orderedRooms);
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
