package com.kshitij.roombooking.service;

import com.kshitij.roombooking.model.BookingResult;
import com.kshitij.roombooking.model.BookingStage;
import com.kshitij.roombooking.model.BookingWindow;
import com.kshitij.roombooking.model.Slot;
import com.kshitij.roombooking.model.SlotPlan;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Books the scheduled room by reading LibCal's availability JSON for exact slot
 * times, then driving the browser to click the matching cell.
 *
 * <p>
 * The grid cells themselves are FullCalendar timeline events carrying nothing
 * but a CSS class — no aria-label, no title, no text — so their times exist
 * only as pixel offsets. Scraping them is not possible; the JSON the page
 * itself fetches is the only reliable source of times.
 */
@Service
public class BookingService {

    private static final Logger logger = LoggerFactory.getLogger(BookingService.class);

    private static final String HOME = "https://carletonu.libcal.com/";
    private static final String ROOMS_LINK = "book study rooms on floors 2-5 in the library";
    private static final String GRID_ENDPOINT = "/spaces/availability/grid";

    /** Library location id, from the room-list URL (?lid=2986). */
    private static final String LID = "2986";
    /** gid=0 returns every item at the location; we filter by itemId ourselves. */
    private static final String GID = "0";

    private static final String TARGET_ROOM = "507";
    private static final int LOOKAHEAD_DAYS = 7;

    private static final Pattern SPACE_ID = Pattern.compile("/space/(\\d+)");

    public BookingResult bookRoom() {
        LocalDate today = LocalDate.now();
        LocalDate targetDate = today.plusDays(LOOKAHEAD_DAYS);

        Optional<BookingWindow> scheduled = BookingSchedule.windowFor(targetDate.getDayOfWeek());
        if (scheduled.isEmpty()) {
            String msg = "Nothing scheduled for " + targetDate.getDayOfWeek() + " (" + targetDate + ").";
            logger.info(msg);
            return BookingResult.skipped(msg);
        }
        BookingWindow window = scheduled.get();

        try {
            // Fail fast: no point spending a minute in the browser without these.
            String username = requireEnv("BOOKING_USERNAME");
            String password = requireEnv("BOOKING_PASSWORD");

            logger.info("Booking room {} on {} ({}) for {} [{} minutes]",
                    TARGET_ROOM, targetDate, targetDate.getDayOfWeek(), window, window.durationMinutes());

            return runBooking(targetDate, window, username, password);

        } catch (BookingException e) {
            logger.error("[{}] {}{}", e.stage(), e.getMessage(),
                    e.detail() == null ? "" : " | " + e.detail(), e);
            return BookingResult.failed(e.stage(), e.getMessage(), e.detail());
        } catch (Exception e) {
            logger.error("Unexpected failure", e);
            return BookingResult.failed(BookingStage.CONFIRM,
                    "Unexpected " + e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    private BookingResult runBooking(LocalDate targetDate, BookingWindow window,
                                     String username, String password) throws BookingException {
        WebDriverManager.chromedriver().setup();
        WebDriver driver = new ChromeDriver(chromeOptions());

        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));
            JavascriptExecutor js = (JavascriptExecutor) driver;

            String roomUrl = resolveRoomUrl(driver, wait, js);
            long itemId = spaceIdFrom(roomUrl);
            logger.info("Room {} -> {} (itemId {})", TARGET_ROOM, roomUrl, itemId);

            driver.get(roomUrl);
            goToDate(driver, wait, LocalDate.now(), targetDate);

            List<Slot> all = AvailabilityGrid.parse(fetchGrid(js, targetDate));
            List<Slot> mine = AvailabilityGrid.allSlotsFor(all, itemId);
            if (mine.isEmpty()) {
                throw new BookingException(BookingStage.GRID_PARSE,
                        "Availability response contained no cells for itemId " + itemId + ".",
                        "response held " + all.size() + " cells across other rooms");
            }
            List<Slot> free = AvailabilityGrid.freeSlotsFor(all, itemId);
            logger.info("Room {} on {}: {} cells, {} free. Free blocks: {}",
                    TARGET_ROOM, targetDate, mine.size(), free.size(), AvailabilityGrid.describe(free));

            // The page renders exactly the free cells as clickable elements. If those
            // counts diverge, the JSON and the DOM disagree and clicking by index
            // would hit the wrong slot -- so refuse rather than mis-book.
            List<WebElement> cells = driver.findElements(By.className("s-lc-eq-avail"));
            if (cells.size() != free.size()) {
                throw new BookingException(BookingStage.GRID_MISMATCH,
                        "JSON reports " + free.size() + " free cells but the page shows " + cells.size()
                                + ". Refusing to click by index.",
                        "This usually means LibCal changed their markup. Free blocks per JSON: "
                                + AvailabilityGrid.describe(free));
            }

            SlotPlan plan = AvailabilityGrid.plan(free, targetDate, window);
            if (!plan.bookable()) {
                String msg = "Room " + TARGET_ROOM + " cannot take " + window + " on " + targetDate + ".";
                logger.info("{} {}", msg, plan.reason());
                return BookingResult.unavailable(msg, plan.reason());
            }

            logger.info("Clicking free cell #{} ({})", plan.freeSlotIndex(), plan.start());
            clickCell(driver, wait, js, cells.get(plan.freeSlotIndex()));
            selectEnd(driver, wait, plan.end());
            submitTimes(driver, wait, js);
            signIn(driver, wait, username, password);
            confirm(driver, wait, js);

            String summary = "Booked room " + TARGET_ROOM + " on " + targetDate + " " + window + ".";
            logger.info(summary);
            return BookingResult.success(summary);

        } finally {
            driver.quit();
        }
    }

    private ChromeOptions chromeOptions() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--window-size=1920,1080");
        return options;
    }

    private static String requireEnv(String name) throws BookingException {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new BookingException(BookingStage.CONFIG,
                    "Environment variable " + name + " is not set.",
                    "Set it as a GitHub Actions secret and pass it through to the container.");
        }
        return value;
    }

    /** Finds the room's own space URL, so we can navigate straight to it. */
    private String resolveRoomUrl(WebDriver driver, WebDriverWait wait, JavascriptExecutor js)
            throws BookingException {
        try {
            driver.get(HOME);
            wait.until(ExpectedConditions.elementToBeClickable(By.linkText(ROOMS_LINK))).click();

            // The list lazy-renders; scroll until it stops growing rather than a fixed count.
            long previous = -1;
            for (int i = 0; i < 25; i++) {
                js.executeScript("window.scrollTo(0, document.body.scrollHeight);");
                Thread.sleep(500);
                long height = (Long) js.executeScript("return document.body.scrollHeight;");
                if (height == previous) {
                    break;
                }
                previous = height;
            }

            List<WebElement> links = wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(
                    By.xpath("//a[contains(@href, '/space/')]")));

            List<String> seen = new java.util.ArrayList<>();
            for (WebElement link : links) {
                // textContent, not getText(): the latter is empty for off-screen elements.
                String name = String.valueOf(link.getAttribute("textContent")).trim();
                seen.add(name);
                if (name.startsWith(TARGET_ROOM)) {
                    return link.getAttribute("href");
                }
            }
            throw new BookingException(BookingStage.ROOM_LOOKUP,
                    "Room " + TARGET_ROOM + " was not in the room list.", "saw: " + seen);
        } catch (BookingException e) {
            throw e;
        } catch (Exception e) {
            throw new BookingException(BookingStage.ROOM_LOOKUP,
                    "Could not resolve room " + TARGET_ROOM + ": " + e.getMessage(), null, e);
        }
    }

    private static long spaceIdFrom(String url) throws BookingException {
        Matcher m = SPACE_ID.matcher(url);
        if (!m.find()) {
            throw new BookingException(BookingStage.ROOM_LOOKUP,
                    "Room URL did not contain a /space/<id> segment.", "url: " + url);
        }
        return Long.parseLong(m.group(1));
    }

    private void goToDate(WebDriver driver, WebDriverWait wait, LocalDate today, LocalDate target)
            throws BookingException {
        try {
            wait.until(ExpectedConditions.elementToBeClickable(
                    By.xpath("//button[contains(text(), 'Go To Date')]"))).click();

            if (target.getMonth() != today.getMonth()) {
                wait.until(ExpectedConditions.elementToBeClickable(
                        By.cssSelector("div.datepicker-days th.next"))).click();
            }

            String day = String.valueOf(target.getDayOfMonth());
            List<WebElement> days = wait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(By.xpath(
                    "//td[contains(@class,'day') and not(contains(@class,'old')) and not(contains(@class,'new'))]")));
            for (WebElement candidate : days) {
                if (candidate.getText().equals(day)) {
                    candidate.click();
                    return;
                }
            }
            throw new BookingException(BookingStage.DATE_NAV,
                    "Day " + day + " was not selectable in the date picker.", "target: " + target);
        } catch (BookingException e) {
            throw e;
        } catch (Exception e) {
            throw new BookingException(BookingStage.DATE_NAV,
                    "Could not move the grid to " + target + ": " + e.getMessage(), null, e);
        }
    }

    /**
     * Asks LibCal for the same availability JSON its own page requests. Issued
     * from inside the page so it inherits the session and origin.
     */
    private String fetchGrid(JavascriptExecutor js, LocalDate target) throws BookingException {
        String body = "lid=" + LID + "&gid=" + GID + "&eid=0&seat=0&seatId=0&zone=0"
                + "&start=" + target + "&end=" + target.plusDays(1)
                + "&pageIndex=0&pageSize=18";
        try {
            Object response = js.executeAsyncScript(
                    "var cb = arguments[arguments.length - 1];"
                            + "fetch(arguments[0], {method: 'POST', credentials: 'same-origin',"
                            + " headers: {'Content-Type': 'application/x-www-form-urlencoded',"
                            + "           'X-Requested-With': 'XMLHttpRequest'}, body: arguments[1]})"
                            + ".then(function (r) { return r.text().then(function (t) {"
                            + "   cb(r.ok ? t : 'HTTP_ERROR ' + r.status + ' ' + t.slice(0, 300)); }); })"
                            + ".catch(function (e) { cb('FETCH_ERROR ' + e); });",
                    GRID_ENDPOINT, body);

            String text = String.valueOf(response);
            if (text.startsWith("HTTP_ERROR") || text.startsWith("FETCH_ERROR")) {
                throw new BookingException(BookingStage.GRID_FETCH,
                        "Availability request failed.", text + " | request body: " + body);
            }
            return text;
        } catch (BookingException e) {
            throw e;
        } catch (Exception e) {
            throw new BookingException(BookingStage.GRID_FETCH,
                    "Availability request failed: " + e.getMessage(), "request body: " + body, e);
        }
    }

    private void clickCell(WebDriver driver, WebDriverWait wait, JavascriptExecutor js, WebElement cell)
            throws BookingException {
        try {
            js.executeScript("arguments[0].scrollIntoView({block: 'center', inline: 'center'});", cell);
            wait.until(ExpectedConditions.elementToBeClickable(cell));
            cell.click();
            wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.cssSelector("select[id^='bookingend']")));
        } catch (Exception e) {
            throw new BookingException(BookingStage.SLOT_CLICK,
                    "Clicked the starting cell but no end-time selector appeared: " + e.getMessage(), null, e);
        }
    }

    /**
     * Picks the end time by its option value, which LibCal renders as an exact
     * {@code yyyy-MM-dd HH:mm:ss} timestamp — no display-text parsing needed.
     */
    private void selectEnd(WebDriver driver, WebDriverWait wait, LocalDateTime end) throws BookingException {
        String wanted = AvailabilityGrid.TIMESTAMP.format(end);
        try {
            WebElement element = wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.cssSelector("select[id^='bookingend']")));
            Select dropdown = new Select(element);

            boolean present = dropdown.getOptions().stream()
                    .anyMatch(o -> wanted.equals(o.getAttribute("value")));
            if (!present) {
                List<String> offered = dropdown.getOptions().stream()
                        .map(o -> o.getAttribute("value")).toList();
                throw new BookingException(BookingStage.END_TIME,
                        "End time " + wanted + " was not offered.", "offered: " + offered);
            }
            dropdown.selectByValue(wanted);
            logger.info("Selected end time {}", wanted);
        } catch (BookingException e) {
            throw e;
        } catch (Exception e) {
            throw new BookingException(BookingStage.END_TIME,
                    "Could not set the end time to " + wanted + ": " + e.getMessage(), null, e);
        }
    }

    private void submitTimes(WebDriver driver, WebDriverWait wait, JavascriptExecutor js)
            throws BookingException {
        try {
            WebElement submit = wait.until(ExpectedConditions.presenceOfElementLocated(By.id("submit_times")));
            js.executeScript("arguments[0].scrollIntoView({block: 'center', inline: 'center'});", submit);
            wait.until(ExpectedConditions.elementToBeClickable(submit)).click();
        } catch (Exception e) {
            throw new BookingException(BookingStage.SUBMIT_TIMES,
                    "Could not submit the chosen times: " + e.getMessage(), null, e);
        }
    }

    private void signIn(WebDriver driver, WebDriverWait wait, String username, String password)
            throws BookingException {
        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(By.id("userNameInput"))).sendKeys(username);
            wait.until(ExpectedConditions.presenceOfElementLocated(By.id("passwordInput"))).sendKeys(password);
            wait.until(ExpectedConditions.elementToBeClickable(By.id("submitButton"))).click();
        } catch (Exception e) {
            throw new BookingException(BookingStage.LOGIN,
                    "Sign-in failed: " + e.getMessage(),
                    "check BOOKING_USERNAME / BOOKING_PASSWORD, and whether ADFS changed its form", e);
        }
    }

    private void confirm(WebDriver driver, WebDriverWait wait, JavascriptExecutor js) throws BookingException {
        try {
            WebElement continueButton = wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.xpath("//button[contains(text(), 'Continue')]")));
            js.executeScript("arguments[0].scrollIntoView({block: 'center', inline: 'center'});", continueButton);
            wait.until(ExpectedConditions.elementToBeClickable(continueButton)).click();

            wait.until(ExpectedConditions.elementToBeClickable(
                    By.xpath("//button[contains(text(), 'Submit my Booking')]"))).click();
        } catch (Exception e) {
            throw new BookingException(BookingStage.CONFIRM,
                    "Could not confirm the booking: " + e.getMessage(), null, e);
        }
    }
}
