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
            LocalDate today = LocalDate.now();
            LocalDate targetDate = today.plusDays(7);

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

            // Get all room links
            List<WebElement> roomLinks = wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(
                    By.xpath("//a[contains(@href, '/space/')]")));

            if (roomLinks.isEmpty()) {
                throw new Exception("No rooms found on the page.");
            }

            // Find 324A or use first room
            WebElement targetRoom = null;
            int room324AIndex = -1;
            for (int i = 0; i < roomLinks.size(); i++) {
                if (roomLinks.get(i).getText().contains("324A")) {
                    targetRoom = roomLinks.get(i);
                    room324AIndex = i;
                    break;
                }
            }
            if (targetRoom == null) {
                targetRoom = roomLinks.get(0);
                room324AIndex = 0;
            }

            String bookedSlotInfo = "";
            boolean slotFound = false;

            // Try 324A first
            String roomName = targetRoom.getText();
            logger.info("Trying preferred room: {}", roomName);
            js.executeScript("arguments[0].scrollIntoView({behavior: 'auto', block: 'center', inline: 'center'});",
                    targetRoom);
            wait.until(ExpectedConditions.elementToBeClickable(targetRoom));
            targetRoom.click();
            Thread.sleep(1000);

            // Navigate to target date
            navigateToDate(driver, wait, today, targetDate);

            // Look for 11:30 slot first
            List<WebElement> availableSlots = driver.findElements(By.className("s-lc-eq-avail"));
            WebElement slot1130 = null;
            WebElement bestSlotAfter1130 = null;
            int bestTimeAfter1130 = Integer.MAX_VALUE;

            for (WebElement slot : availableSlots) {
                String label = slot.getAttribute("aria-label");
                if (label != null) {
                    String time = extractTime(label);
                    if (time != null) {
                        int mins = convertToMinutes(time);
                        if (label.contains("11:30")) {
                            slot1130 = slot;
                            break; // Found 11:30, stop
                        } else if (mins > 11 * 60 + 30 && mins < bestTimeAfter1130) {
                            // Track the earliest slot after 11:30
                            bestTimeAfter1130 = mins;
                            bestSlotAfter1130 = slot;
                        }
                    }
                }
            }

            // Use 11:30 if found, otherwise use best slot after 11:30
            WebElement slotToBook = (slot1130 != null) ? slot1130 : bestSlotAfter1130;

            if (slotToBook != null) {
                bookedSlotInfo = slotToBook.getAttribute("aria-label");
                if (slot1130 != null) {
                    logger.info("Found 11:30 slot in {}: {}", roomName, bookedSlotInfo);
                } else {
                    logger.info("No 11:30 slot. Using next available in {}: {}", roomName, bookedSlotInfo);
                }
                js.executeScript("arguments[0].scrollIntoView({behavior: 'auto', block: 'center', inline: 'center'});",
                        slotToBook);
                wait.until(ExpectedConditions.elementToBeClickable(slotToBook));
                slotToBook.click();
                Thread.sleep(1000);
                slotFound = true;
            }

            // If nothing found in 324A, go back and try other rooms
            if (!slotFound) {
                logger.info("No suitable slot in {}. Trying other rooms...", roomName);

                // Go back to room list
                driver.get("https://carletonu.libcal.com/");
                Thread.sleep(1000);

                bookStudyRoomsLink = wait.until(ExpectedConditions.elementToBeClickable(
                        By.linkText("book study rooms on floors 2-5 in the library")));
                bookStudyRoomsLink.click();
                Thread.sleep(1000);

                for (int i = 0; i < 5; i++) {
                    js.executeScript("window.scrollBy(0, 1000);");
                    Thread.sleep(1000);
                }

                roomLinks = wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(
                        By.xpath("//a[contains(@href, '/space/')]")));

                // Try each room (except 324A which we already tried)
                for (int i = 0; i < roomLinks.size() && !slotFound; i++) {
                    if (i == room324AIndex)
                        continue; // Skip 324A

                    WebElement room = roomLinks.get(i);
                    roomName = room.getText();
                    logger.info("Trying room: {}", roomName);

                    js.executeScript(
                            "arguments[0].scrollIntoView({behavior: 'auto', block: 'center', inline: 'center'});",
                            room);
                    wait.until(ExpectedConditions.elementToBeClickable(room));
                    room.click();
                    Thread.sleep(1000);

                    navigateToDate(driver, wait, today, targetDate);

                    availableSlots = driver.findElements(By.className("s-lc-eq-avail"));
                    slot1130 = null;
                    bestSlotAfter1130 = null;
                    bestTimeAfter1130 = Integer.MAX_VALUE;

                    for (WebElement slot : availableSlots) {
                        String label = slot.getAttribute("aria-label");
                        if (label != null) {
                            String time = extractTime(label);
                            if (time != null) {
                                int mins = convertToMinutes(time);
                                if (label.contains("11:30")) {
                                    slot1130 = slot;
                                    break;
                                } else if (mins > 11 * 60 + 30 && mins < bestTimeAfter1130) {
                                    bestTimeAfter1130 = mins;
                                    bestSlotAfter1130 = slot;
                                }
                            }
                        }
                    }

                    slotToBook = (slot1130 != null) ? slot1130 : bestSlotAfter1130;

                    if (slotToBook != null) {
                        bookedSlotInfo = slotToBook.getAttribute("aria-label");
                        if (slot1130 != null) {
                            logger.info("Found 11:30 slot in {}: {}", roomName, bookedSlotInfo);
                        } else {
                            logger.info("Using next available in {}: {}", roomName, bookedSlotInfo);
                        }
                        js.executeScript(
                                "arguments[0].scrollIntoView({behavior: 'auto', block: 'center', inline: 'center'});",
                                slotToBook);
                        wait.until(ExpectedConditions.elementToBeClickable(slotToBook));
                        slotToBook.click();
                        Thread.sleep(1000);
                        slotFound = true;
                    } else {
                        // Go back to try next room
                        driver.get("https://carletonu.libcal.com/");
                        Thread.sleep(1000);

                        bookStudyRoomsLink = wait.until(ExpectedConditions.elementToBeClickable(
                                By.linkText("book study rooms on floors 2-5 in the library")));
                        bookStudyRoomsLink.click();
                        Thread.sleep(1000);

                        for (int j = 0; j < 5; j++) {
                            js.executeScript("window.scrollBy(0, 1000);");
                            Thread.sleep(1000);
                        }

                        roomLinks = wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(
                                By.xpath("//a[contains(@href, '/space/')]")));
                    }
                }
            }

            if (!slotFound) {
                throw new Exception("No available slots found in any room.");
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
