package com.kshitij.roombooking.service;

import com.kshitij.roombooking.model.BookingResult;
import com.kshitij.roombooking.model.BookingStage;
import com.kshitij.roombooking.model.BookingWindow;
import com.kshitij.roombooking.model.DayPlan;
import com.kshitij.roombooking.model.Profile;
import com.kshitij.roombooking.model.ProfileResult;
import com.kshitij.roombooking.model.Slot;
import com.kshitij.roombooking.model.SlotPlan;
import com.kshitij.roombooking.model.TimeBlock;
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

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Books each profile's scheduled room by reading LibCal's availability JSON for exact slot
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

    /** Bookings are made exactly one week ahead. */
    static final int LOOKAHEAD_DAYS = 7;
    /**
     * "Today" is the library's date, not the server's. The container clock is
     * UTC, which runs ahead of Ottawa every evening -- a run started then would
     * otherwise book 8 days out.
     */
    static final ZoneId LIBRARY_ZONE = ZoneId.of("America/Toronto");

    private static final Pattern SPACE_ID = Pattern.compile("/space/(\\d+)");

    private final ScheduleSource scheduleSource;

    public BookingService(ScheduleSource scheduleSource) {
        this.scheduleSource = scheduleSource;
    }

    /** A room this run already booked, so later profiles don't waste a browser on it. */
    private record Claim(BookingWindow window, String profileName) {
    }

    /**
     * Books for every profile, one after another. Profiles are processed in the
     * order the scheduler returns them; a room booked for an earlier profile is
     * skipped for later ones with an overlapping window, so they go straight to
     * their next backup room.
     */
    /** The date tonight's run books for. */
    public static LocalDate targetDate() {
        return targetDate(Clock.system(LIBRARY_ZONE));
    }

    static LocalDate targetDate(Clock clock) {
        return libraryToday(clock).plusDays(LOOKAHEAD_DAYS);
    }

    static LocalDate libraryToday(Clock clock) {
        return LocalDate.now(clock.withZone(LIBRARY_ZONE));
    }

    public List<ProfileResult> bookAll() {
        LocalDate targetDate = targetDate();

        List<Profile> profiles;
        try {
            profiles = scheduleSource.load();
        } catch (BookingException e) {
            logger.error("[{}] {}{}", e.stage(), e.getMessage(), e.detail() == null ? "" : " | " + e.detail(), e);
            return List.of(new ProfileResult("-", "config",
                    BookingResult.failed(e.stage(), e.getMessage(), e.detail())));
        }
        if (profiles.isEmpty()) {
            return List.of(new ProfileResult("-", "config",
                    BookingResult.skipped("No profiles are configured in the scheduler.")));
        }

        Map<String, List<Claim>> claimed = new HashMap<>();
        List<ProfileResult> results = new ArrayList<>();
        for (Profile profile : profiles) {
            BookingResult result = bookFor(profile, targetDate, claimed);
            ProfileResult attributed = new ProfileResult(profile.id(), profile.name(), result);
            logger.info(attributed.render());
            results.add(attributed);
        }
        return results;
    }

    BookingResult bookFor(Profile profile, LocalDate targetDate, Map<String, List<Claim>> claimed) {
        if (profile.problem() != null) {
            return BookingResult.failed(BookingStage.CONFIG, "This profile's schedule couldn't be read.", profile.problem());
        }
        Optional<DayPlan> scheduled = profile.planFor(targetDate.getDayOfWeek());
        if (scheduled.isEmpty()) {
            return BookingResult.skipped("Nothing scheduled for " + targetDate.getDayOfWeek() + " (" + targetDate + ").");
        }
        if (!profile.hasCredentials()) {
            return BookingResult.failed(BookingStage.CONFIG,
                    "No Carleton login is saved for this profile.",
                    "Add it under Settings in the scheduler (or set BOOKING_USERNAME / BOOKING_PASSWORD).");
        }

        // Each block is its own LibCal booking. A failure (as opposed to a taken
        // room) usually repeats -- bad login, changed markup -- so stop there.
        List<BookingResult> results = new ArrayList<>();
        for (TimeBlock block : scheduled.get().blocks()) {
            BookingResult result = bookBlock(profile, block, targetDate, claimed);
            results.add(result);
            if (result.status() == BookingResult.Status.FAILED) {
                break;
            }
        }
        return combine(results, scheduled.get().blocks().size());
    }

    /**
     * Folds per-block outcomes into one result for the profile: FAILED if any
     * block failed, SUCCESS if at least one block was booked (the summary says
     * how many), otherwise UNAVAILABLE.
     */
    static BookingResult combine(List<BookingResult> results, int blockCount) {
        if (results.size() == 1 && blockCount == 1) {
            return results.get(0);
        }
        List<String> lines = results.stream().map(BookingResult::render).toList();
        String detail = String.join(" || ", lines);
        long booked = results.stream().filter(r -> r.status() == BookingResult.Status.SUCCESS).count();
        Optional<BookingResult> failed = results.stream()
                .filter(r -> r.status() == BookingResult.Status.FAILED).findFirst();
        if (failed.isPresent()) {
            String prefix = booked > 0 ? "Booked " + booked + " of " + blockCount + " blocks, then: " : "";
            return new BookingResult(BookingResult.Status.FAILED, prefix + failed.get().summary(), detail);
        }
        if (booked > 0) {
            return new BookingResult(BookingResult.Status.SUCCESS,
                    "Booked " + booked + " of " + blockCount + " blocks.", detail);
        }
        return new BookingResult(BookingResult.Status.UNAVAILABLE,
                "None of the " + blockCount + " blocks could be booked.", detail);
    }

    private BookingResult bookBlock(Profile profile, TimeBlock block, LocalDate targetDate,
                                    Map<String, List<Claim>> claimed) {
        BookingWindow window = block.window();
        List<String> rooms = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (String room : block.rooms()) {
            Optional<Claim> clash = claimed.getOrDefault(RoomNames.key(room), List.of()).stream()
                    .filter(c -> overlaps(c.window(), window))
                    .findFirst();
            if (clash.isPresent()) {
                notes.add(room + ": taken by " + clash.get().profileName() + " this run");
            } else {
                rooms.add(room);
            }
        }
        if (rooms.isEmpty()) {
            return BookingResult.unavailable(
                    "Every room on the list was already booked by another profile for " + window + " on " + targetDate + ".",
                    String.join("; ", notes));
        }

        try {
            logger.info("[{}] Booking {} on {} ({}) for {} [{} minutes], rooms in order: {}",
                    profile.name(), rooms.get(0), targetDate, targetDate.getDayOfWeek(), window,
                    window.durationMinutes(), rooms);
            return runBooking(targetDate, window, rooms, notes, profile, claimed);
        } catch (BookingException e) {
            logger.error("[{}] [{}] {}{}", profile.name(), e.stage(), e.getMessage(),
                    e.detail() == null ? "" : " | " + e.detail(), e);
            return BookingResult.failed(e.stage(), e.getMessage(), e.detail());
        } catch (Exception e) {
            logger.error("[{}] Unexpected failure", profile.name(), e);
            return BookingResult.failed(BookingStage.CONFIRM,
                    "Unexpected " + e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    static boolean overlaps(BookingWindow a, BookingWindow b) {
        return a.start().isBefore(b.end()) && b.start().isBefore(a.end());
    }

    private BookingResult runBooking(LocalDate targetDate, BookingWindow window, List<String> rooms,
                                     List<String> notes, Profile profile, Map<String, List<Claim>> claimed)
            throws BookingException {
        WebDriverManager.chromedriver().setup();
        WebDriver driver = new ChromeDriver(chromeOptions());

        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));
            JavascriptExecutor js = (JavascriptExecutor) driver;

            Map<String, String> urls = resolveRoomUrls(driver, wait, js, rooms);

            for (int choice = 0; choice < rooms.size(); choice++) {
                String room = rooms.get(choice);
                String roomUrl = urls.get(room);
                if (roomUrl == null) {
                    notes.add(room + ": not in LibCal's room list");
                    continue;
                }
                long itemId = spaceIdFrom(roomUrl);
                logger.info("Room {} -> {} (itemId {})", room, roomUrl, itemId);

                driver.get(roomUrl);
                goToDate(driver, wait, libraryToday(Clock.systemUTC()), targetDate);

                List<Slot> all = AvailabilityGrid.parse(fetchGrid(js, targetDate));
                List<Slot> mine = AvailabilityGrid.allSlotsFor(all, itemId);
                if (mine.isEmpty()) {
                    throw new BookingException(BookingStage.GRID_PARSE,
                            "Availability response contained no cells for room " + room + " (itemId " + itemId + ").",
                            "response held " + all.size() + " cells across other rooms");
                }
                List<Slot> free = AvailabilityGrid.freeSlotsFor(all, itemId);
                logger.info("Room {} on {}: {} cells, {} free. Free blocks: {}",
                        room, targetDate, mine.size(), free.size(), AvailabilityGrid.describe(free));

                // The page renders exactly the free cells as clickable elements. If those
                // counts diverge, the JSON and the DOM disagree and clicking by index
                // would hit the wrong slot -- so refuse rather than mis-book.
                List<WebElement> cells = driver.findElements(By.className("s-lc-eq-avail"));
                if (cells.size() != free.size()) {
                    throw new BookingException(BookingStage.GRID_MISMATCH,
                            "JSON reports " + free.size() + " free cells for room " + room
                                    + " but the page shows " + cells.size() + ". Refusing to click by index.",
                            "This usually means LibCal changed their markup. Free blocks per JSON: "
                                    + AvailabilityGrid.describe(free));
                }

                SlotPlan slotPlan = AvailabilityGrid.plan(free, targetDate, window);
                if (!slotPlan.bookable()) {
                    logger.info("Room {} cannot take {} on {}: {}", room, window, targetDate, slotPlan.reason());
                    notes.add(room + ": " + slotPlan.reason());
                    continue;
                }

                logger.info("Clicking free cell #{} ({})", slotPlan.freeSlotIndex(), slotPlan.start());
                clickCell(driver, wait, js, cells.get(slotPlan.freeSlotIndex()));
                selectEnd(driver, wait, slotPlan.end());
                submitTimes(driver, wait, js);
                signIn(driver, wait, profile.username(), profile.password());
                confirm(driver, wait, js);

                claimed.computeIfAbsent(RoomNames.key(room), k -> new ArrayList<>())
                        .add(new Claim(window, profile.name()));
                String summary = "Booked room " + room + " on " + targetDate + " " + window
                        + (choice == 0 && notes.isEmpty() ? "." : " (backup choice).");
                return notes.isEmpty()
                        ? BookingResult.success(summary)
                        : new BookingResult(BookingResult.Status.SUCCESS, summary, String.join("; ", notes));
            }

            return BookingResult.unavailable(
                    "None of " + rooms + " could take " + window + " on " + targetDate + ".",
                    String.join("; ", notes));

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

    /**
     * Loads the room list once and finds the space URL for each wanted room.
     * Rooms that aren't listed are simply absent from the map; it's only an
     * error when none of them are found.
     */
    private Map<String, String> resolveRoomUrls(WebDriver driver, WebDriverWait wait, JavascriptExecutor js,
                                                List<String> rooms) throws BookingException {
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

            Map<String, String> found = new LinkedHashMap<>();
            List<String> seen = new ArrayList<>();
            for (WebElement link : links) {
                // textContent, not getText(): the latter is empty for off-screen elements.
                String name = String.valueOf(link.getAttribute("textContent")).trim();
                seen.add(name);
                for (String room : rooms) {
                    if (!found.containsKey(room) && RoomNames.matches(name, room)) {
                        found.put(room, link.getAttribute("href"));
                    }
                }
            }
            if (found.isEmpty()) {
                throw new BookingException(BookingStage.ROOM_LOOKUP,
                        "None of " + rooms + " were in the room list.", "saw: " + seen);
            }
            return found;
        } catch (BookingException e) {
            throw e;
        } catch (Exception e) {
            throw new BookingException(BookingStage.ROOM_LOOKUP,
                    "Could not load the room list: " + e.getMessage(), null, e);
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
