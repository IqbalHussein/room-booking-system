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
        // Setup ChromeDriver automatically using WebDriverManager
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        // Essential flags for running in Docker container
        options.addArguments("--headless=new");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
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

            // Scroll down
            for (int i = 0; i < 5; i++) {
                js.executeScript("window.scrollBy(0, 1000);");
                Thread.sleep(1000);
            }

            // Find available rooms
            List<WebElement> roomLinks = wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(
                    By.xpath("//a[contains(@href, '/space/')]")));

            if (roomLinks.isEmpty()) {
                throw new Exception("No rooms found on the page.");
            }

            // Click first available room
            WebElement firstRoom = roomLinks.get(0);
            String roomName = firstRoom.getText();
            logger.info("Clicked on room: {}", roomName);
            js.executeScript("arguments[0].scrollIntoView({behavior: 'auto', block: 'center', inline: 'center'});",
                    firstRoom);
            wait.until(ExpectedConditions.elementToBeClickable(firstRoom));
            firstRoom.click();

            Thread.sleep(1000);

            // Go to Date
            WebElement goToDateButton = wait.until(ExpectedConditions.elementToBeClickable(
                    By.xpath("//button[contains(text(), 'Go To Date')]")));
            goToDateButton.click();
            Thread.sleep(1000);

            LocalDate today = LocalDate.now();
            LocalDate oneWeekFromNow = today.plusDays(7);
            String nextWeekDate = String.valueOf(oneWeekFromNow.getDayOfMonth());

            // Select next week's date
            List<WebElement> allDates = wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(
                    By.xpath("//td[contains(@class, 'day')]")));

            boolean dateFound = false;
            for (WebElement date : allDates) {
                if (date.getText().equals(nextWeekDate) && !date.getAttribute("class").contains("old")) {
                    date.click();
                    dateFound = true;
                    break;
                }
            }

            if (!dateFound)
                throw new Exception("Could not locate target date on calendar.");

            logger.info("Clicked on next week's date.");
            Thread.sleep(1000);

            // Multi-day loop logic
            boolean slotFound = false;
            String bookedSlotInfo = "";

            for (int i = 0; i < 5; i++) {
                List<WebElement> availableSlots = driver.findElements(By.className("s-lc-eq-avail"));

                if (!availableSlots.isEmpty()) {
                    WebElement firstSlot = availableSlots.get(0);
                    bookedSlotInfo = firstSlot.getAttribute("aria-label");
                    logger.info("Clicked on first available timeslot: {}", bookedSlotInfo);

                    js.executeScript(
                            "arguments[0].scrollIntoView({behavior: 'auto', block: 'center', inline: 'center'});",
                            firstSlot);
                    wait.until(ExpectedConditions.elementToBeClickable(firstSlot));
                    firstSlot.click();
                    Thread.sleep(1000);
                    slotFound = true;
                    break;
                }

                logger.info("No slots found for date offset {}. Checking next day...", i);

                WebElement nextDayButton = wait.until(ExpectedConditions.elementToBeClickable(
                        By.xpath("//button[contains(text(), 'Go To Date')]")));
                nextDayButton.click();
                Thread.sleep(500);

                LocalDate targetDate = oneWeekFromNow.plusDays(i + 1);
                String dayText = String.valueOf(targetDate.getDayOfMonth());

                List<WebElement> datesHandler = wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(
                        By.xpath(
                                "//td[contains(@class, 'day') and not(contains(@class, 'old')) and not(contains(@class, 'disabled'))]")));

                for (WebElement d : datesHandler) {
                    if (d.getText().equals(dayText)) {
                        d.click();
                        break;
                    }
                }
                Thread.sleep(1000);
            }

            if (!slotFound)
                throw new Exception("No available time slots found after checking 5 days.");

            // Duration Dropdown
            WebElement dropdownElement = wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("select")));
            Select dropdown = new Select(dropdownElement);
            dropdown.selectByIndex(dropdown.getOptions().size() - 1);
            Thread.sleep(1000);

            // Submit Times
            Thread.sleep(1000);
            WebElement submitButton = wait.until(ExpectedConditions.presenceOfElementLocated(By.id("submit_times")));
            js.executeScript("arguments[0].scrollIntoView({behavior: 'auto', block: 'center', inline: 'center'});",
                    submitButton);
            Thread.sleep(500);
            wait.until(ExpectedConditions.elementToBeClickable(submitButton));
            submitButton.click();
            Thread.sleep(2000);

            // Login - SECURE CREDENTIAL INJECTION
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

            // Continue Button - Robust Logic
            Thread.sleep(1000);
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
}
