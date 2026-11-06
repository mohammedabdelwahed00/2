import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LibrarySystem - a complete, self-contained library management simulation.
 *
 * Features:
 *  - Catalog with ISBN-13 validation, multiple copies per book, search and autocomplete
 *  - Members with tiered privileges (student, standard, premium, staff)
 *  - Loans, renewals, returns, lost items
 *  - Fines with caps, payments and waivers
 *  - Reservations with hold queues and expiry
 *  - Event bus and audit log
 *  - Reports, recommendations and a text-table renderer
 *  - A tiny command interpreter and a built-in test harness
 *
 * Compile: javac LibrarySystem.java
 * Run:     java LibrarySystem
 */
public class LibrarySystem {

    // =====================================================================
    // SECTION 1: ENUMERATIONS
    // =====================================================================

    /** Book genres used for classification and recommendations. */
    public enum Genre {
        FICTION("Fiction"),
        NONFICTION("Non-fiction"),
        SCIENCE("Science"),
        HISTORY("History"),
        TECHNOLOGY("Technology"),
        FANTASY("Fantasy"),
        MYSTERY("Mystery"),
        BIOGRAPHY("Biography"),
        CHILDREN("Children"),
        ROMANCE("Romance");

        private final String label;

        Genre(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** Membership tiers and their privileges. */
    public enum MemberType {
        STUDENT(3, 14, "0.25"),
        STANDARD(5, 21, "0.50"),
        PREMIUM(10, 30, "0.10"),
        STAFF(15, 45, "0.00");

        private final int maxLoans;
        private final int loanDays;
        private final BigDecimal finePerDay;

        MemberType(int maxLoans, int loanDays, String finePerDay) {
            this.maxLoans = maxLoans;
            this.loanDays = loanDays;
            this.finePerDay = new BigDecimal(finePerDay);
        }

        public int getMaxLoans() {
            return maxLoans;
        }

        public int getLoanDays() {
            return loanDays;
        }

        public BigDecimal getFinePerDay() {
            return finePerDay;
        }
    }

    /** State of a physical copy of a book. */
    public enum CopyStatus {
        AVAILABLE, ON_LOAN, RESERVED, LOST, DAMAGED
    }

    /** State of a loan record. */
    public enum LoanStatus {
        ACTIVE, OVERDUE, RETURNED, LOST
    }

    /** State of a fine. */
    public enum FineStatus {
        UNPAID, PAID, WAIVED
    }

    /** Types of events published on the bus. */
    public enum EventType {
        BOOK_ADDED, MEMBER_REGISTERED, MEMBER_BLOCKED, MEMBER_UNBLOCKED,
        LOAN_CREATED, LOAN_RENEWED, LOAN_RETURNED, LOAN_LOST,
        FINE_ISSUED, FINE_PAID, FINE_WAIVED,
        RESERVATION_CREATED, RESERVATION_READY, RESERVATION_EXPIRED, RESERVATION_FULFILLED
    }

    // =====================================================================
    // SECTION 2: EXCEPTIONS
    // =====================================================================

    /** Base class for all domain errors. */
    public static class LibraryException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public LibraryException(String message) {
            super(message);
        }
    }

    /** Thrown when an entity cannot be located. */
    public static class ItemNotFoundException extends LibraryException {
        private static final long serialVersionUID = 1L;

        public ItemNotFoundException(String kind, String id) {
            super(kind + " not found: " + id);
        }
    }

    /** Thrown when a member exceeds their loan quota. */
    public static class LoanLimitException extends LibraryException {
        private static final long serialVersionUID = 1L;

        public LoanLimitException(String message) {
            super(message);
        }
    }

    /** Thrown when no copy can be lent. */
    public static class ItemUnavailableException extends LibraryException {
        private static final long serialVersionUID = 1L;

        public ItemUnavailableException(String message) {
            super(message);
        }
    }

    /** Thrown when a blocked member attempts to borrow. */
    public static class MemberBlockedException extends LibraryException {
        private static final long serialVersionUID = 1L;

        public MemberBlockedException(String message) {
            super(message);
        }
    }

    /** Thrown on invalid input. */
    public static class ValidationException extends LibraryException {
        private static final long serialVersionUID = 1L;

        public ValidationException(String message) {
            super(message);
        }
    }

    /** Thrown on invalid payments or fine operations. */
    public static class PaymentException extends LibraryException {
        private static final long serialVersionUID = 1L;

        public PaymentException(String message) {
            super(message);
        }
    }

    // =====================================================================
    // SECTION 3: UTILITIES
    // =====================================================================

    /** Generates sequential ids such as B-0001. */
    public static final class IdGenerator {
        private static final Map<String, AtomicInteger> COUNTERS = new HashMap<>();

        private IdGenerator() {
        }

        public static synchronized String next(String prefix) {
            AtomicInteger counter = COUNTERS.computeIfAbsent(prefix, k -> new AtomicInteger(0));
            return String.format("%s-%04d", prefix, counter.incrementAndGet());
        }

        public static synchronized void reset() {
            COUNTERS.clear();
        }
    }

    /** A controllable clock so that simulations can move through time. */
    public static class SimulatedClock {
        private LocalDate today;

        public SimulatedClock(LocalDate start) {
            this.today = start;
        }

        public LocalDate today() {
            return today;
        }

        public void advanceDays(int days) {
            if (days < 0) {
                throw new ValidationException("Cannot move the clock backwards");
            }
            today = today.plusDays(days);
        }
    }

    /** Input validation helpers. */
    public static final class Validator {
        private static final Pattern EMAIL =
                Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

        private Validator() {
        }

        public static String requireText(String value, String field) {
            if (value == null || value.trim().isEmpty()) {
                throw new ValidationException(field + " must not be empty");
            }
            return value.trim();
        }

        public static String requireEmail(String value) {
            String v = requireText(value, "email");
            if (!EMAIL.matcher(v).matches()) {
                throw new ValidationException("Invalid email address: " + v);
            }
            return v.toLowerCase();
        }

        public static int requireRange(int value, int min, int max, String field) {
            if (value < min || value > max) {
                throw new ValidationException(field + " must be between " + min + " and " + max);
            }
            return value;
        }

        /** Validates an ISBN-13 including its check digit. Hyphens and spaces are ignored. */
        public static String requireIsbn13(String raw) {
            String digits = requireText(raw, "isbn").replace("-", "").replace(" ", "");
            if (digits.length() != 13 || !digits.chars().allMatch(Character::isDigit)) {
                throw new ValidationException("ISBN-13 must contain 13 digits: " + raw);
            }
            int sum = 0;
            for (int i = 0; i < 12; i++) {
                int d = digits.charAt(i) - '0';
                sum += (i % 2 == 0) ? d : d * 3;
            }
            int check = (10 - (sum % 10)) % 10;
            if (check != digits.charAt(12) - '0') {
                throw new ValidationException("ISBN-13 check digit is wrong: " + raw);
            }
            return digits;
        }
    }

    /** Money helpers: two decimal places, half-up rounding. */
    public static final class Money {
        public static final BigDecimal ZERO = new BigDecimal("0.00");

        private Money() {
        }

        public static BigDecimal of(String value) {
            return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
        }

        public static BigDecimal round(BigDecimal value) {
            return value.setScale(2, RoundingMode.HALF_UP);
        }

        public static String format(BigDecimal value) {
            return "$" + round(value).toPlainString();
        }
    }

    /** Renders rows of strings as an aligned text table. */
    public static class TextTable {
        private final List<String> headers;
        private final List<List<String>> rows = new ArrayList<>();

        public TextTable(String... headers) {
            this.headers = List.of(headers);
        }

        public TextTable addRow(Object... cells) {
            if (cells.length != headers.size()) {
                throw new IllegalArgumentException("Expected " + headers.size() + " cells");
            }
            List<String> row = new ArrayList<>();
            for (Object cell : cells) {
                row.add(cell == null ? "" : String.valueOf(cell));
            }
            rows.add(row);
            return this;
        }

        public int rowCount() {
            return rows.size();
        }

        public String render() {
            int[] widths = new int[headers.size()];
            for (int c = 0; c < widths.length; c++) {
                widths[c] = headers.get(c).length();
                for (List<String> row : rows) {
                    widths[c] = Math.max(widths[c], row.get(c).length());
                }
            }
            StringBuilder sb = new StringBuilder();
            appendSeparator(sb, widths);
            appendRow(sb, headers, widths);
            appendSeparator(sb, widths);
            for (List<String> row : rows) {
                appendRow(sb, row, widths);
            }
            appendSeparator(sb, widths);
            return sb.toString();
        }

        private void appendSeparator(StringBuilder sb, int[] widths) {
            sb.append('+');
            for (int w : widths) {
                sb.append("-".repeat(w + 2)).append('+');
            }
            sb.append('\n');
        }

        private void appendRow(StringBuilder sb, List<String> cells, int[] widths) {
            sb.append('|');
            for (int c = 0; c < widths.length; c++) {
                sb.append(' ').append(pad(cells.get(c), widths[c])).append(" |");
            }
            sb.append('\n');
        }

        private String pad(String text, int width) {
            StringBuilder sb = new StringBuilder(text);
            while (sb.length() < width) {
                sb.append(' ');
            }
            return sb.toString();
        }
    }

    /** A simple least-recently-used cache. */
    public static class LruCache<K, V> extends LinkedHashMap<K, V> {
        private static final long serialVersionUID = 1L;
        private final int capacity;

        public LruCache(int capacity) {
            super(16, 0.75f, true);
            this.capacity = capacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > capacity;
        }
    }

    /** A prefix tree used for title and author autocomplete. */
    public static class Trie {
        private static class Node {
            final Map<Character, Node> children = new TreeMap<>();
            boolean terminal;
            String word;
        }

        private final Node root = new Node();

        public void insert(String word) {
            String key = word.toLowerCase();
            Node node = root;
            for (char ch : key.toCharArray()) {
                node = node.children.computeIfAbsent(ch, k -> new Node());
            }
            node.terminal = true;
            node.word = word;
        }

        public List<String> suggestions(String prefix, int limit) {
            Node node = root;
            for (char ch : prefix.toLowerCase().toCharArray()) {
                node = node.children.get(ch);
                if (node == null) {
                    return Collections.emptyList();
                }
            }
            List<String> out = new ArrayList<>();
            collect(node, out, limit);
            return out;
        }

        private void collect(Node node, List<String> out, int limit) {
            if (out.size() >= limit) {
                return;
            }
            if (node.terminal) {
                out.add(node.word);
            }
            for (Node child : node.children.values()) {
                collect(child, out, limit);
            }
        }
    }

    // =====================================================================
    // SECTION 4: DOMAIN MODEL
    // =====================================================================

    /** Base class for all stored objects. */
    public abstract static class Entity {
        protected final String id;

        protected Entity(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            return id.equals(((Entity) o).id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    /** A bibliographic record (the title, not a physical copy). */
    public static class Book extends Entity {
        private final String isbn;
        private final String title;
        private final String author;
        private final Genre genre;
        private final int year;
        private final BigDecimal price;

        public Book(String isbn, String title, String author, Genre genre, int year, BigDecimal price) {
            super(IdGenerator.next("B"));
            this.isbn = isbn;
            this.title = title;
            this.author = author;
            this.genre = genre;
            this.year = year;
            this.price = price;
        }

        public String getIsbn() {
            return isbn;
        }

        public String getTitle() {
            return title;
        }

        public String getAuthor() {
            return author;
        }

        public Genre getGenre() {
            return genre;
        }

        public int getYear() {
            return year;
        }

        public BigDecimal getPrice() {
            return price;
        }

        @Override
        public String toString() {
            return title + " by " + author + " (" + year + ")";
        }
    }

    /** A physical copy of a book. */
    public static class BookCopy extends Entity {
        private final String bookId;
        private CopyStatus status = CopyStatus.AVAILABLE;
        private int condition = 5;

        public BookCopy(String bookId) {
            super(IdGenerator.next("C"));
            this.bookId = bookId;
        }

        public String getBookId() {
            return bookId;
        }

        public CopyStatus getStatus() {
            return status;
        }

        public void setStatus(CopyStatus status) {
            this.status = status;
        }

        public int getCondition() {
            return condition;
        }

        public void setCondition(int condition) {
            this.condition = condition;
        }
    }

    /** A person who can borrow books. */
    public static class Member extends Entity {
        private final String name;
        private final String email;
        private MemberType type;
        private final LocalDate joined;
        private boolean blocked;

        public Member(String name, String email, MemberType type, LocalDate joined) {
            super(IdGenerator.next("M"));
            this.name = name;
            this.email = email;
            this.type = type;
            this.joined = joined;
        }

        public String getName() {
            return name;
        }

        public String getEmail() {
            return email;
        }

        public MemberType getType() {
            return type;
        }

        public void setType(MemberType type) {
            this.type = type;
        }

        public LocalDate getJoined() {
            return joined;
        }

        public boolean isBlocked() {
            return blocked;
        }

        public void setBlocked(boolean blocked) {
            this.blocked = blocked;
        }

        @Override
        public String toString() {
            return name + " [" + type + "]";
        }
    }

    /** A loan of one copy to one member. */
    public static class Loan extends Entity {
        private final String copyId;
        private final String bookId;
        private final String memberId;
        private final LocalDate loanDate;
        private LocalDate dueDate;
        private LocalDate returnDate;
        private int renewals;
        private LoanStatus status = LoanStatus.ACTIVE;

        public Loan(String copyId, String bookId, String memberId, LocalDate loanDate, LocalDate dueDate) {
            super(IdGenerator.next("L"));
            this.copyId = copyId;
            this.bookId = bookId;
            this.memberId = memberId;
            this.loanDate = loanDate;
            this.dueDate = dueDate;
        }

        public String getCopyId() {
            return copyId;
        }

        public String getBookId() {
            return bookId;
        }

        public String getMemberId() {
            return memberId;
        }

        public LocalDate getLoanDate() {
            return loanDate;
        }

        public LocalDate getDueDate() {
            return dueDate;
        }

        public void setDueDate(LocalDate dueDate) {
            this.dueDate = dueDate;
        }

        public LocalDate getReturnDate() {
            return returnDate;
        }

        public void setReturnDate(LocalDate returnDate) {
            this.returnDate = returnDate;
        }

        public int getRenewals() {
            return renewals;
        }

        public void incrementRenewals() {
            renewals++;
        }

        public LoanStatus getStatus() {
            return status;
        }

        public void setStatus(LoanStatus status) {
            this.status = status;
        }

        public boolean isOpen() {
            return status == LoanStatus.ACTIVE || status == LoanStatus.OVERDUE;
        }

        public long daysLate(LocalDate asOf) {
            LocalDate end = returnDate != null ? returnDate : asOf;
            return Math.max(0, ChronoUnit.DAYS.between(dueDate, end));
        }
    }

    /** A monetary penalty for a late or lost item. */
    public static class Fine extends Entity {
        private final String memberId;
        private final String loanId;
        private final BigDecimal amount;
        private final LocalDate issued;
        private final String reason;
        private FineStatus status = FineStatus.UNPAID;
        private BigDecimal paid = Money.ZERO;

        public Fine(String memberId, String loanId, BigDecimal amount, LocalDate issued, String reason) {
            super(IdGenerator.next("F"));
            this.memberId = memberId;
            this.loanId = loanId;
            this.amount = Money.round(amount);
            this.issued = issued;
            this.reason = reason;
        }

        public String getMemberId() {
            return memberId;
        }

        public String getLoanId() {
            return loanId;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public LocalDate getIssued() {
            return issued;
        }

        public String getReason() {
            return reason;
        }

        public FineStatus getStatus() {
            return status;
        }

        public BigDecimal getPaid() {
            return paid;
        }

        public BigDecimal getRemaining() {
            return status == FineStatus.WAIVED ? Money.ZERO : Money.round(amount.subtract(paid));
        }

        public void applyPayment(BigDecimal payment) {
            paid = Money.round(paid.add(payment));
            if (paid.compareTo(amount) >= 0) {
                status = FineStatus.PAID;
            }
        }

        public void waive() {
            status = FineStatus.WAIVED;
        }
    }

    /** A request to be given the next available copy of a book. */
    public static class Reservation extends Entity {
        private final String bookId;
        private final String memberId;
        private final LocalDate created;
        private String heldCopyId;
        private LocalDate holdExpires;
        private boolean closed;

        public Reservation(String bookId, String memberId, LocalDate created) {
            super(IdGenerator.next("R"));
            this.bookId = bookId;
            this.memberId = memberId;
            this.created = created;
        }

        public String getBookId() {
            return bookId;
        }

        public String getMemberId() {
            return memberId;
        }

        public LocalDate getCreated() {
            return created;
        }

        public String getHeldCopyId() {
            return heldCopyId;
        }

        public LocalDate getHoldExpires() {
            return holdExpires;
        }

        public boolean isClosed() {
            return closed;
        }

        public boolean isReady() {
            return !closed && heldCopyId != null;
        }

        public void hold(String copyId, LocalDate expires) {
            this.heldCopyId = copyId;
            this.holdExpires = expires;
        }

        public void close() {
            this.closed = true;
        }
    }

    // =====================================================================
    // SECTION 5: EVENTS
    // =====================================================================

    /** An immutable record of something that happened. */
    public static final class Event {
        private final EventType type;
        private final String message;
        private final LocalDateTime timestamp;

        public Event(EventType type, String message) {
            this.type = type;
            this.message = message;
            this.timestamp = LocalDateTime.now();
        }

        public EventType getType() {
            return type;
        }

        public String getMessage() {
            return message;
        }

        public LocalDateTime getTimestamp() {
            return timestamp;
        }

        @Override
        public String toString() {
            return "[" + type + "] " + message;
        }
    }

    /** Publish/subscribe dispatcher. */
    public static class EventBus {
        private final Map<EventType, List<Consumer<Event>>> subscribers = new EnumMap<>(EventType.class);
        private final List<Consumer<Event>> global = new ArrayList<>();

        public void subscribe(EventType type, Consumer<Event> handler) {
            subscribers.computeIfAbsent(type, k -> new ArrayList<>()).add(handler);
        }

        public void subscribeAll(Consumer<Event> handler) {
            global.add(handler);
        }

        public void publish(EventType type, String message) {
            Event event = new Event(type, message);
            for (Consumer<Event> handler : global) {
                handler.accept(event);
            }
            for (Consumer<Event> handler : subscribers.getOrDefault(type, Collections.emptyList())) {
                handler.accept(event);
            }
        }
    }

    /** Keeps every event in memory for later inspection. */
    public static class AuditLog {
        private final List<Event> entries = new ArrayList<>();

        public AuditLog(EventBus bus) {
            bus.subscribeAll(entries::add);
        }

        public int size() {
            return entries.size();
        }

        public List<Event> last(int n) {
            int from = Math.max(0, entries.size() - n);
            return new ArrayList<>(entries.subList(from, entries.size()));
        }

        public long count(EventType type) {
            return entries.stream().filter(e -> e.getType() == type).count();
        }
    }

    // =====================================================================
    // SECTION 6: REPOSITORIES
    // =====================================================================

    /** A generic in-memory store that preserves insertion order. */
    public static class Repository<T extends Entity> {
        private final Map<String, T> items = new LinkedHashMap<>();
        private final String kind;

        public Repository(String kind) {
            this.kind = kind;
        }

        public T save(T item) {
            items.put(item.getId(), item);
            return item;
        }

        public Optional<T> find(String id) {
            return Optional.ofNullable(items.get(id));
        }

        public T require(String id) {
            return find(id).orElseThrow(() -> new ItemNotFoundException(kind, id));
        }

        public List<T> all() {
            return new ArrayList<>(items.values());
        }

        public List<T> where(Predicate<T> predicate) {
            return items.values().stream().filter(predicate).collect(Collectors.toList());
        }

        public Optional<T> firstWhere(Predicate<T> predicate) {
            return items.values().stream().filter(predicate).findFirst();
        }

        public boolean remove(String id) {
            return items.remove(id) != null;
        }

        public int count() {
            return items.size();
        }
    }

    // =====================================================================
    // SECTION 7: SERVICES
    // =====================================================================

    /** Manages books, copies, search and autocomplete. */
    public static class CatalogService {
        private final Repository<Book> books = new Repository<>("Book");
        private final Repository<BookCopy> copies = new Repository<>("Copy");
        private final Map<String, Book> byIsbn = new HashMap<>();
        private final Trie trie = new Trie();
        private final LruCache<String, List<Book>> searchCache = new LruCache<>(32);
        private final EventBus bus;

        public CatalogService(EventBus bus) {
            this.bus = bus;
        }

        public Book addBook(String isbn, String title, String author, Genre genre, int year,
                            String price, int copyCount) {
            String cleanIsbn = Validator.requireIsbn13(isbn);
            if (byIsbn.containsKey(cleanIsbn)) {
                throw new ValidationException("Book with this ISBN already exists: " + cleanIsbn);
            }
            Validator.requireRange(year, 1400, 2100, "year");
            Validator.requireRange(copyCount, 1, 50, "copies");
            Book book = new Book(cleanIsbn, Validator.requireText(title, "title"),
                    Validator.requireText(author, "author"), genre, year, Money.of(price));
            books.save(book);
            byIsbn.put(cleanIsbn, book);
            trie.insert(book.getTitle());
            trie.insert(book.getAuthor());
            for (int i = 0; i < copyCount; i++) {
                copies.save(new BookCopy(book.getId()));
            }
            searchCache.clear();
            bus.publish(EventType.BOOK_ADDED, book + " with " + copyCount + " copies");
            return book;
        }

        public Book requireBook(String id) {
            return books.require(id);
        }

        public BookCopy requireCopy(String id) {
            return copies.require(id);
        }

        public Optional<Book> findByIsbn(String isbn) {
            return Optional.ofNullable(byIsbn.get(isbn.replace("-", "")));
        }

        public List<Book> allBooks() {
            return books.all();
        }

        public List<BookCopy> copiesOf(String bookId) {
            return copies.where(c -> c.getBookId().equals(bookId));
        }

        public int availableCount(String bookId) {
            return (int) copiesOf(bookId).stream().filter(c -> c.getStatus() == CopyStatus.AVAILABLE).count();
        }

        public Optional<BookCopy> firstAvailableCopy(String bookId) {
            return copies.firstWhere(c -> c.getBookId().equals(bookId) && c.getStatus() == CopyStatus.AVAILABLE);
        }

        /** Scores each book against the query: title match counts most, then author, then genre. */
        public List<Book> search(String query) {
            String q = Validator.requireText(query, "query").toLowerCase();
            List<Book> cached = searchCache.get(q);
            if (cached != null) {
                return cached;
            }
            Map<Book, Integer> scores = new HashMap<>();
            String[] tokens = q.split("\\s+");
            for (Book book : books.all()) {
                int score = 0;
                String title = book.getTitle().toLowerCase();
                String author = book.getAuthor().toLowerCase();
                String genre = book.getGenre().getLabel().toLowerCase();
                for (String token : tokens) {
                    if (title.contains(token)) {
                        score += title.startsWith(token) ? 6 : 4;
                    }
                    if (author.contains(token)) {
                        score += 3;
                    }
                    if (genre.contains(token)) {
                        score += 1;
                    }
                }
                if (score > 0) {
                    scores.put(book, score);
                }
            }
            List<Book> result = scores.entrySet().stream()
                    .sorted(Map.Entry.<Book, Integer>comparingByValue().reversed()
                            .thenComparing(e -> e.getKey().getTitle()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            searchCache.put(q, result);
            return result;
        }

        public List<String> autocomplete(String prefix, int limit) {
            return trie.suggestions(prefix, limit);
        }

        public List<Book> byGenre(Genre genre) {
            return books.where(b -> b.getGenre() == genre);
        }
    }

    /** Registers and manages members. */
    public static class MemberService {
        private final Repository<Member> members = new Repository<>("Member");
        private final SimulatedClock clock;
        private final EventBus bus;

        public MemberService(SimulatedClock clock, EventBus bus) {
            this.clock = clock;
            this.bus = bus;
        }

        public Member register(String name, String email, MemberType type) {
            String cleanEmail = Validator.requireEmail(email);
            if (members.firstWhere(m -> m.getEmail().equals(cleanEmail)).isPresent()) {
                throw new ValidationException("Email already registered: " + cleanEmail);
            }
            Member member = new Member(Validator.requireText(name, "name"), cleanEmail, type, clock.today());
            members.save(member);
            bus.publish(EventType.MEMBER_REGISTERED, member + " registered");
            return member;
        }

        public Member require(String id) {
            return members.require(id);
        }

        public List<Member> all() {
            return members.all();
        }

        public void block(String id, String reason) {
            Member m = members.require(id);
            if (!m.isBlocked()) {
                m.setBlocked(true);
                bus.publish(EventType.MEMBER_BLOCKED, m + " blocked: " + reason);
            }
        }

        public void unblock(String id) {
            Member m = members.require(id);
            if (m.isBlocked()) {
                m.setBlocked(false);
                bus.publish(EventType.MEMBER_UNBLOCKED, m + " unblocked");
            }
        }

        public void changeType(String id, MemberType type) {
            members.require(id).setType(type);
        }
    }

    /** Issues fines, accepts payments and handles waivers. */
    public static class FineService {
        public static final BigDecimal BLOCK_THRESHOLD = Money.of("10.00");

        private final Repository<Fine> fines = new Repository<>("Fine");
        private final SimulatedClock clock;
        private final EventBus bus;

        public FineService(SimulatedClock clock, EventBus bus) {
            this.clock = clock;
            this.bus = bus;
        }

        public Fine issue(String memberId, String loanId, BigDecimal amount, String reason) {
            Fine fine = new Fine(memberId, loanId, amount, clock.today(), reason);
            fines.save(fine);
            bus.publish(EventType.FINE_ISSUED, Money.format(fine.getAmount()) + " for " + memberId + " (" + reason + ")");
            return fine;
        }

        public List<Fine> unpaidFor(String memberId) {
            return fines.where(f -> f.getMemberId().equals(memberId) && f.getStatus() == FineStatus.UNPAID);
        }

        public BigDecimal outstanding(String memberId) {
            return unpaidFor(memberId).stream()
                    .map(Fine::getRemaining)
                    .reduce(Money.ZERO, BigDecimal::add);
        }

        /** Applies a payment to the oldest fines first. Returns the amount actually used. */
        public BigDecimal pay(String memberId, BigDecimal amount) {
            if (amount.signum() <= 0) {
                throw new PaymentException("Payment must be positive");
            }
            BigDecimal left = Money.round(amount);
            BigDecimal used = Money.ZERO;
            List<Fine> unpaid = new ArrayList<>(unpaidFor(memberId));
            unpaid.sort(Comparator.comparing(Fine::getIssued));
            for (Fine fine : unpaid) {
                if (left.signum() <= 0) {
                    break;
                }
                BigDecimal part = fine.getRemaining().min(left);
                fine.applyPayment(part);
                left = Money.round(left.subtract(part));
                used = Money.round(used.add(part));
            }
            if (used.signum() == 0) {
                throw new PaymentException("Member has no unpaid fines: " + memberId);
            }
            bus.publish(EventType.FINE_PAID, Money.format(used) + " received from " + memberId);
            return used;
        }

        public void waive(String fineId) {
            Fine fine = fines.require(fineId);
            if (fine.getStatus() != FineStatus.UNPAID) {
                throw new PaymentException("Only unpaid fines can be waived");
            }
            fine.waive();
            bus.publish(EventType.FINE_WAIVED, fineId + " waived");
        }

        public List<Fine> all() {
            return fines.all();
        }

        public BigDecimal totalIssued() {
            return fines.all().stream().map(Fine::getAmount).reduce(Money.ZERO, BigDecimal::add);
        }

        public BigDecimal totalCollected() {
            return fines.all().stream().map(Fine::getPaid).reduce(Money.ZERO, BigDecimal::add);
        }
    }

    /** Manages the queue of members waiting for a title. */
    public static class ReservationService {
        public static final int HOLD_DAYS = 3;

        private final Repository<Reservation> reservations = new Repository<>("Reservation");
        private final CatalogService catalog;
        private final SimulatedClock clock;
        private final EventBus bus;

        public ReservationService(CatalogService catalog, SimulatedClock clock, EventBus bus) {
            this.catalog = catalog;
            this.clock = clock;
            this.bus = bus;
        }

        public Reservation reserve(Member member, Book book) {
            boolean already = reservations.firstWhere(r -> !r.isClosed()
                    && r.getMemberId().equals(member.getId())
                    && r.getBookId().equals(book.getId())).isPresent();
            if (already) {
                throw new ValidationException(member.getName() + " already reserved " + book.getTitle());
            }
            Reservation r = reservations.save(new Reservation(book.getId(), member.getId(), clock.today()));
            bus.publish(EventType.RESERVATION_CREATED, member.getName() + " reserved " + book.getTitle());
            return r;
        }

        public List<Reservation> queueFor(String bookId) {
            return reservations.where(r -> !r.isClosed() && r.getBookId().equals(bookId)).stream()
                    .sorted(Comparator.comparing(Reservation::getCreated))
                    .collect(Collectors.toList());
        }

        public boolean hasWaiting(String bookId) {
            return queueFor(bookId).stream().anyMatch(r -> !r.isReady());
        }

        /** Gives a freshly returned copy to the first waiting member, if any. */
        public boolean holdForNext(BookCopy copy) {
            Optional<Reservation> next = queueFor(copy.getBookId()).stream()
                    .filter(r -> !r.isReady())
                    .findFirst();
            if (next.isEmpty()) {
                return false;
            }
            Reservation r = next.get();
            r.hold(copy.getId(), clock.today().plusDays(HOLD_DAYS));
            copy.setStatus(CopyStatus.RESERVED);
            bus.publish(EventType.RESERVATION_READY, "Copy " + copy.getId() + " held for " + r.getMemberId()
                    + " until " + r.getHoldExpires());
            return true;
        }

        public Optional<Reservation> readyFor(String memberId, String bookId) {
            return reservations.firstWhere(r -> r.isReady()
                    && r.getMemberId().equals(memberId) && r.getBookId().equals(bookId));
        }

        public void fulfill(Reservation r) {
            r.close();
            bus.publish(EventType.RESERVATION_FULFILLED, r.getId() + " fulfilled");
        }

        /** Releases holds that were not collected in time and passes copies on. */
        public int expireHolds() {
            int expired = 0;
            for (Reservation r : reservations.where(Reservation::isReady)) {
                if (clock.today().isAfter(r.getHoldExpires())) {
                    BookCopy copy = catalog.requireCopy(r.getHeldCopyId());
                    r.close();
                    copy.setStatus(CopyStatus.AVAILABLE);
                    bus.publish(EventType.RESERVATION_EXPIRED, r.getId() + " expired");
                    holdForNext(copy);
                    expired++;
                }
            }
            return expired;
        }

        public int openCount() {
            return reservations.where(r -> !r.isClosed()).size();
        }
    }

    /** The heart of the system: borrowing, renewing and returning. */
    public static class LoanService {
        public static final int MAX_RENEWALS = 2;

        private final Repository<Loan> loans = new Repository<>("Loan");
        private final CatalogService catalog;
        private final MemberService members;
        private final FineService fines;
        private final ReservationService reservations;
        private final SimulatedClock clock;
        private final EventBus bus;

        public LoanService(CatalogService catalog, MemberService members, FineService fines,
                           ReservationService reservations, SimulatedClock clock, EventBus bus) {
            this.catalog = catalog;
            this.members = members;
            this.fines = fines;
            this.reservations = reservations;
            this.clock = clock;
            this.bus = bus;
        }

        public Loan borrow(String memberId, String bookId) {
            Member member = members.require(memberId);
            Book book = catalog.requireBook(bookId);
            if (member.isBlocked()) {
                throw new MemberBlockedException(member.getName() + " is blocked");
            }
            if (fines.outstanding(memberId).compareTo(FineService.BLOCK_THRESHOLD) > 0) {
                throw new MemberBlockedException(member.getName() + " owes "
                        + Money.format(fines.outstanding(memberId)));
            }
            if (activeLoansFor(memberId).size() >= member.getType().getMaxLoans()) {
                throw new LoanLimitException(member.getName() + " reached the limit of "
                        + member.getType().getMaxLoans() + " loans");
            }
            boolean hasTitle = activeLoansFor(memberId).stream().anyMatch(l -> l.getBookId().equals(bookId));
            if (hasTitle) {
                throw new ValidationException(member.getName() + " already has " + book.getTitle());
            }

            Optional<Reservation> held = reservations.readyFor(memberId, bookId);
            BookCopy copy;
            if (held.isPresent()) {
                copy = catalog.requireCopy(held.get().getHeldCopyId());
                reservations.fulfill(held.get());
            } else {
                copy = catalog.firstAvailableCopy(bookId).orElseThrow(
                        () -> new ItemUnavailableException("No copy of '" + book.getTitle() + "' is available"));
            }

            copy.setStatus(CopyStatus.ON_LOAN);
            LocalDate due = clock.today().plusDays(member.getType().getLoanDays());
            Loan loan = loans.save(new Loan(copy.getId(), bookId, memberId, clock.today(), due));
            bus.publish(EventType.LOAN_CREATED, member.getName() + " borrowed " + book.getTitle()
                    + ", due " + due);
            return loan;
        }

        public Loan renew(String loanId) {
            Loan loan = loans.require(loanId);
            refreshStatus(loan);
            if (!loan.isOpen()) {
                throw new ValidationException("Loan is not open: " + loanId);
            }
            if (loan.getStatus() == LoanStatus.OVERDUE) {
                throw new ValidationException("Overdue loans cannot be renewed");
            }
            if (loan.getRenewals() >= MAX_RENEWALS) {
                throw new ValidationException("Renewal limit reached for " + loanId);
            }
            if (reservations.hasWaiting(loan.getBookId())) {
                throw new ValidationException("Another member is waiting for this title");
            }
            Member member = members.require(loan.getMemberId());
            loan.setDueDate(loan.getDueDate().plusDays(member.getType().getLoanDays()));
            loan.incrementRenewals();
            bus.publish(EventType.LOAN_RENEWED, loanId + " renewed until " + loan.getDueDate());
            return loan;
        }

        /** Returns a copy. The condition (1-5) is recorded; 1 means it is too damaged to lend. */
        public Loan returnCopy(String copyId, int conditionAfter) {
            Validator.requireRange(conditionAfter, 1, 5, "condition");
            Loan loan = loans.firstWhere(l -> l.isOpen() && l.getCopyId().equals(copyId))
                    .orElseThrow(() -> new ItemNotFoundException("Open loan for copy", copyId));
            Member member = members.require(loan.getMemberId());
            BookCopy copy = catalog.requireCopy(copyId);
            Book book = catalog.requireBook(loan.getBookId());

            loan.setReturnDate(clock.today());
            loan.setStatus(LoanStatus.RETURNED);
            copy.setCondition(conditionAfter);

            long late = loan.daysLate(clock.today());
            if (late > 0) {
                BigDecimal raw = member.getType().getFinePerDay().multiply(BigDecimal.valueOf(late));
                BigDecimal fine = raw.min(book.getPrice());
                if (fine.signum() > 0) {
                    fines.issue(member.getId(), loan.getId(), fine, late + " day(s) late: " + book.getTitle());
                }
            }

            if (conditionAfter == 1) {
                copy.setStatus(CopyStatus.DAMAGED);
            } else if (!reservations.holdForNext(copy)) {
                copy.setStatus(CopyStatus.AVAILABLE);
            }
            bus.publish(EventType.LOAN_RETURNED, member.getName() + " returned " + book.getTitle());
            return loan;
        }

        public Loan markLost(String loanId) {
            Loan loan = loans.require(loanId);
            if (!loan.isOpen()) {
                throw new ValidationException("Loan is not open: " + loanId);
            }
            Book book = catalog.requireBook(loan.getBookId());
            loan.setStatus(LoanStatus.LOST);
            catalog.requireCopy(loan.getCopyId()).setStatus(CopyStatus.LOST);
            BigDecimal charge = book.getPrice().add(Money.of("5.00"));
            fines.issue(loan.getMemberId(), loanId, charge, "Lost: " + book.getTitle());
            bus.publish(EventType.LOAN_LOST, loanId + " marked lost");
            return loan;
        }

        /** Moves ACTIVE loans that passed their due date to OVERDUE. */
        public int refreshOverdue() {
            int changed = 0;
            for (Loan loan : loans.all()) {
                LoanStatus before = loan.getStatus();
                refreshStatus(loan);
                if (before != loan.getStatus()) {
                    changed++;
                }
            }
            return changed;
        }

        private void refreshStatus(Loan loan) {
            if (loan.getStatus() == LoanStatus.ACTIVE && clock.today().isAfter(loan.getDueDate())) {
                loan.setStatus(LoanStatus.OVERDUE);
            }
        }

        public List<Loan> activeLoansFor(String memberId) {
            return loans.where(l -> l.isOpen() && l.getMemberId().equals(memberId));
        }

        public List<Loan> overdueLoans() {
            refreshOverdue();
            return loans.where(l -> l.getStatus() == LoanStatus.OVERDUE);
        }

        public List<Loan> historyFor(String memberId) {
            return loans.where(l -> l.getMemberId().equals(memberId));
        }

        public List<Loan> all() {
            return loans.all();
        }

        public Loan require(String id) {
            return loans.require(id);
        }
    }

    // =====================================================================
    // SECTION 8: ANALYTICS AND RECOMMENDATIONS
    // =====================================================================

    /** Builds reports from the current state of the system. */
    public static class ReportService {
        private final CatalogService catalog;
        private final MemberService members;
        private final LoanService loans;
        private final FineService fines;
        private final SimulatedClock clock;

        public ReportService(CatalogService catalog, MemberService members, LoanService loans,
                             FineService fines, SimulatedClock clock) {
            this.catalog = catalog;
            this.members = members;
            this.loans = loans;
            this.fines = fines;
            this.clock = clock;
        }

        public String inventoryReport() {
            TextTable table = new TextTable("Book", "Title", "Author", "Genre", "Total", "Available");
            for (Book b : catalog.allBooks()) {
                int total = catalog.copiesOf(b.getId()).size();
                table.addRow(b.getId(), b.getTitle(), b.getAuthor(), b.getGenre().getLabel(),
                        total, catalog.availableCount(b.getId()));
            }
            return table.render();
        }

        public String overdueReport() {
            TextTable table = new TextTable("Loan", "Member", "Title", "Due", "Days late", "Accrued");
            for (Loan l : loans.overdueLoans()) {
                Member m = members.require(l.getMemberId());
                Book b = catalog.requireBook(l.getBookId());
                long late = l.daysLate(clock.today());
                BigDecimal accrued = m.getType().getFinePerDay().multiply(BigDecimal.valueOf(late)).min(b.getPrice());
                table.addRow(l.getId(), m.getName(), b.getTitle(), l.getDueDate(), late, Money.format(accrued));
            }
            return table.render();
        }

        public Map<String, Long> loanCountsByBook() {
            return loans.all().stream()
                    .collect(Collectors.groupingBy(Loan::getBookId, Collectors.counting()));
        }

        public String popularityReport(int top) {
            Map<String, Long> counts = loanCountsByBook();
            TextTable table = new TextTable("Rank", "Title", "Loans");
            List<Map.Entry<String, Long>> sorted = counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                            .thenComparing(e -> catalog.requireBook(e.getKey()).getTitle()))
                    .limit(top)
                    .collect(Collectors.toList());
            int rank = 1;
            for (Map.Entry<String, Long> e : sorted) {
                table.addRow(rank++, catalog.requireBook(e.getKey()).getTitle(), e.getValue());
            }
            return table.render();
        }

        public Map<Genre, Long> loansByGenre() {
            Map<Genre, Long> result = new EnumMap<>(Genre.class);
            for (Loan l : loans.all()) {
                Genre g = catalog.requireBook(l.getBookId()).getGenre();
                result.merge(g, 1L, Long::sum);
            }
            return result;
        }

        public String genreReport() {
            TextTable table = new TextTable("Genre", "Loans", "Share");
            Map<Genre, Long> byGenre = loansByGenre();
            long total = byGenre.values().stream().mapToLong(Long::longValue).sum();
            for (Map.Entry<Genre, Long> e : byGenre.entrySet()) {
                double share = total == 0 ? 0 : 100.0 * e.getValue() / total;
                table.addRow(e.getKey().getLabel(), e.getValue(), String.format("%.1f%%", share));
            }
            return table.render();
        }

        public String financeReport() {
            TextTable table = new TextTable("Metric", "Value");
            table.addRow("Fines issued", Money.format(fines.totalIssued()));
            table.addRow("Fines collected", Money.format(fines.totalCollected()));
            BigDecimal open = fines.all().stream()
                    .filter(f -> f.getStatus() == FineStatus.UNPAID)
                    .map(Fine::getRemaining)
                    .reduce(Money.ZERO, BigDecimal::add);
            table.addRow("Fines outstanding", Money.format(open));
            long waived = fines.all().stream().filter(f -> f.getStatus() == FineStatus.WAIVED).count();
            table.addRow("Fines waived (count)", waived);
            return table.render();
        }

        public String memberReport() {
            TextTable table = new TextTable("Member", "Name", "Type", "Active loans", "Owes", "Blocked");
            for (Member m : members.all()) {
                table.addRow(m.getId(), m.getName(), m.getType(), loans.activeLoansFor(m.getId()).size(),
                        Money.format(fines.outstanding(m.getId())), m.isBlocked() ? "yes" : "no");
            }
            return table.render();
        }

        /** Exports all loans as CSV text. */
        public String loansCsv() {
            StringBuilder sb = new StringBuilder("loan,member,book,copy,loaned,due,returned,status\n");
            for (Loan l : loans.all()) {
                sb.append(String.join(",", l.getId(), l.getMemberId(), l.getBookId(), l.getCopyId(),
                        String.valueOf(l.getLoanDate()), String.valueOf(l.getDueDate()),
                        l.getReturnDate() == null ? "" : l.getReturnDate().toString(),
                        l.getStatus().name())).append('\n');
            }
            return sb.toString();
        }
    }

    /** Suggests books based on a member's reading history. */
    public static class RecommendationEngine {
        private final CatalogService catalog;
        private final LoanService loans;

        public RecommendationEngine(CatalogService catalog, LoanService loans) {
            this.catalog = catalog;
            this.loans = loans;
        }

        public Map<Genre, Integer> tasteProfile(String memberId) {
            Map<Genre, Integer> profile = new EnumMap<>(Genre.class);
            for (Loan l : loans.historyFor(memberId)) {
                profile.merge(catalog.requireBook(l.getBookId()).getGenre(), 1, Integer::sum);
            }
            return profile;
        }

        public List<Book> recommend(String memberId, int limit) {
            Map<Genre, Integer> profile = tasteProfile(memberId);
            Collection<String> alreadyRead = loans.historyFor(memberId).stream()
                    .map(Loan::getBookId)
                    .collect(Collectors.toSet());
            Map<String, Long> popularity = loans.all().stream()
                    .collect(Collectors.groupingBy(Loan::getBookId, Collectors.counting()));

            return catalog.allBooks().stream()
                    .filter(b -> !alreadyRead.contains(b.getId()))
                    .filter(b -> profile.containsKey(b.getGenre()))
                    .sorted(Comparator
                            .comparingInt((Book b) -> profile.get(b.getGenre())).reversed()
                            .thenComparing((Book b) -> popularity.getOrDefault(b.getId(), 0L),
                                    Comparator.reverseOrder())
                            .thenComparing(Book::getTitle))
                    .limit(limit)
                    .collect(Collectors.toList());
        }
    }

    // =====================================================================
    // SECTION 9: APPLICATION FACADE AND COMMAND INTERPRETER
    // =====================================================================

    /** Wires every service together. */
    public static class Library {
        public final SimulatedClock clock;
        public final EventBus bus = new EventBus();
        public final AuditLog audit = new AuditLog(bus);
        public final CatalogService catalog = new CatalogService(bus);
        public final MemberService members;
        public final FineService fines;
        public final ReservationService reservations;
        public final LoanService loans;
        public final ReportService reports;
        public final RecommendationEngine recommender;

        public Library(LocalDate startDate) {
            this.clock = new SimulatedClock(startDate);
            this.members = new MemberService(clock, bus);
            this.fines = new FineService(clock, bus);
            this.reservations = new ReservationService(catalog, clock, bus);
            this.loans = new LoanService(catalog, members, fines, reservations, clock, bus);
            this.reports = new ReportService(catalog, members, loans, fines, clock);
            this.recommender = new RecommendationEngine(catalog, loans);

            // Automatically block members who become heavily indebted.
            bus.subscribe(EventType.FINE_ISSUED, event -> {
                for (Member m : members.all()) {
                    if (fines.outstanding(m.getId()).compareTo(FineService.BLOCK_THRESHOLD) > 0) {
                        members.block(m.getId(), "unpaid fines above threshold");
                    }
                }
            });
            // Automatically unblock members who settled their debt.
            bus.subscribe(EventType.FINE_PAID, event -> {
                for (Member m : members.all()) {
                    if (m.isBlocked() && fines.outstanding(m.getId()).compareTo(FineService.BLOCK_THRESHOLD) <= 0) {
                        members.unblock(m.getId());
                    }
                }
            });
        }

        /** Advances time, then refreshes overdue statuses and expires old holds. */
        public void advance(int days) {
            clock.advanceDays(days);
            loans.refreshOverdue();
            reservations.expireHolds();
        }
    }

    /** Parses and runs plain-text commands. */
    public static class CommandInterpreter {
        private final Library lib;

        public CommandInterpreter(Library lib) {
            this.lib = lib;
        }

        public String execute(String line) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 0 || parts[0].isEmpty()) {
                return "";
            }
            String cmd = parts[0].toLowerCase();
            try {
                switch (cmd) {
                    case "help":
                        return help();
                    case "search":
                        return search(rest(parts, 1));
                    case "suggest":
                        return String.join(", ", lib.catalog.autocomplete(rest(parts, 1), 5));
                    case "borrow":
                        return borrow(parts);
                    case "return":
                        return giveBack(parts);
                    case "renew":
                        return renew(parts);
                    case "reserve":
                        return reserve(parts);
                    case "advance":
                        lib.advance(Integer.parseInt(parts[1]));
                        return "Date is now " + lib.clock.today();
                    case "pay":
                        return pay(parts);
                    case "lost":
                        return "Marked lost: " + lib.loans.markLost(parts[1]).getId();
                    case "report":
                        return report(parts.length > 1 ? parts[1] : "");
                    case "recommend":
                        return recommend(parts[1]);
                    default:
                        return "Unknown command: " + cmd + " (try 'help')";
                }
            } catch (LibraryException e) {
                return "ERROR: " + e.getMessage();
            } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                return "ERROR: bad arguments for '" + cmd + "'";
            }
        }

        private String rest(String[] parts, int from) {
            return String.join(" ", java.util.Arrays.copyOfRange(parts, from, parts.length));
        }

        private String help() {
            return String.join("\n",
                    "Commands:",
                    "  search <text>            suggest <prefix>",
                    "  borrow <member> <book>   return <copy> [condition]",
                    "  renew <loan>             reserve <member> <book>",
                    "  advance <days>           pay <member> <amount>",
                    "  lost <loan>              recommend <member>",
                    "  report inventory|overdue|popular|genre|finance|members");
        }

        private String search(String query) {
            List<Book> found = lib.catalog.search(query);
            if (found.isEmpty()) {
                return "No results for '" + query + "'";
            }
            TextTable table = new TextTable("Book", "Title", "Author", "Available");
            for (Book b : found) {
                table.addRow(b.getId(), b.getTitle(), b.getAuthor(), lib.catalog.availableCount(b.getId()));
            }
            return table.render();
        }

        private String borrow(String[] parts) {
            Loan loan = lib.loans.borrow(parts[1], parts[2]);
            return "Loan " + loan.getId() + " created, copy " + loan.getCopyId() + ", due " + loan.getDueDate();
        }

        private String giveBack(String[] parts) {
            int condition = parts.length > 2 ? Integer.parseInt(parts[2]) : 5;
            Loan loan = lib.loans.returnCopy(parts[1], condition);
            return "Returned " + loan.getId() + " on " + loan.getReturnDate();
        }

        private String renew(String[] parts) {
            Loan loan = lib.loans.renew(parts[1]);
            return "Renewed " + loan.getId() + ", new due date " + loan.getDueDate();
        }

        private String reserve(String[] parts) {
            Member m = lib.members.require(parts[1]);
            Book b = lib.catalog.requireBook(parts[2]);
            Reservation r = lib.reservations.reserve(m, b);
            return "Reservation " + r.getId() + " queued at position "
                    + lib.reservations.queueFor(b.getId()).size();
        }

        private String pay(String[] parts) {
            BigDecimal used = lib.fines.pay(parts[1], Money.of(parts[2]));
            return "Applied " + Money.format(used) + ", remaining "
                    + Money.format(lib.fines.outstanding(parts[1]));
        }

        private String recommend(String memberId) {
            List<Book> books = lib.recommender.recommend(memberId, 3);
            if (books.isEmpty()) {
                return "Not enough history for recommendations";
            }
            return books.stream().map(Book::toString).collect(Collectors.joining("\n"));
        }

        private String report(String which) {
            switch (which) {
                case "inventory":
                    return lib.reports.inventoryReport();
                case "overdue":
                    return lib.reports.overdueReport();
                case "popular":
                    return lib.reports.popularityReport(5);
                case "genre":
                    return lib.reports.genreReport();
                case "finance":
                    return lib.reports.financeReport();
                case "members":
                    return lib.reports.memberReport();
                default:
                    return "Unknown report. Use inventory, overdue, popular, genre, finance or members.";
            }
        }
    }

    // =====================================================================
    // SECTION 10: SAMPLE DATA
    // =====================================================================

    /** Loads a small but varied catalog and some members. */
    public static final class SampleData {
        private SampleData() {
        }

        public static void load(Library lib) {
            CatalogService c = lib.catalog;
            c.addBook("978-0-306-40615-7", "The Silent Orbit", "Mara Quinn", Genre.SCIENCE, 2015, "24.50", 2);
            c.addBook("978-0-13-110362-7", "Practical Algorithms", "Ivo Hartmann", Genre.TECHNOLOGY, 2009, "59.00", 3);
            c.addBook("978-3-16-148410-0", "Ashes of Carthage", "Lena Okafor", Genre.HISTORY, 2012, "18.75", 1);
            c.addBook("978-1-86197-876-9", "The Lantern Keeper", "Tobias Reed", Genre.FANTASY, 2018, "15.99", 2);
            c.addBook("978-0-596-52068-7", "Midnight on Harbor Street", "Sofia Marin", Genre.MYSTERY, 2020, "13.50", 2);
            c.addBook("978-0-14-312779-6", "Letters from the Delta", "Amara Diallo", Genre.FICTION, 2005, "11.25", 1);
            c.addBook("978-1-4028-9462-6", "A Life in Equations", "Noor Haddad", Genre.BIOGRAPHY, 2016, "21.00", 1);
            c.addBook("978-0-7432-7356-5", "Counting Stars with Pip", "Elsa Brandt", Genre.CHILDREN, 2019, "9.99", 3);
            c.addBook("978-0-393-04002-9", "The Last Waltz in Vienna", "Clara Weiss", Genre.ROMANCE, 2011, "12.40", 1);
            c.addBook("978-0-345-39180-3", "Dragons of the Ember Coast", "Tobias Reed", Genre.FANTASY, 2021, "17.99", 2);

            lib.members.register("Alice Martin", "alice@example.com", MemberType.STUDENT);
            lib.members.register("Bruno Keller", "bruno@example.com", MemberType.STANDARD);
            lib.members.register("Chen Wei", "chen@example.com", MemberType.PREMIUM);
            lib.members.register("Dana Ortiz", "dana@example.com", MemberType.STAFF);
        }
    }

    // =====================================================================
    // SECTION 11: A MINIMAL TEST HARNESS
    // =====================================================================

    /** A tiny assertion library so the file has no external dependencies. */
    public static class TestRunner {
        private int passed;
        private int failed;
        private final List<String> failures = new ArrayList<>();

        public void run(String name, Runnable test) {
            IdGenerator.reset();
            try {
                test.run();
                passed++;
                System.out.println("  PASS  " + name);
            } catch (AssertionError | RuntimeException e) {
                failed++;
                failures.add(name + ": " + e.getMessage());
                System.out.println("  FAIL  " + name + " -> " + e.getMessage());
            }
        }

        public static void check(boolean condition, String message) {
            if (!condition) {
                throw new AssertionError(message);
            }
        }

        public static void assertEquals(Object expected, Object actual, String message) {
            if (!Objects.equals(expected, actual)) {
                throw new AssertionError(message + " (expected " + expected + " but was " + actual + ")");
            }
        }

        public static void assertThrows(Class<? extends Throwable> type, Runnable action, String message) {
            try {
                action.run();
            } catch (Throwable t) {
                if (type.isInstance(t)) {
                    return;
                }
                throw new AssertionError(message + " (wrong exception: " + t.getClass().getSimpleName() + ")");
            }
            throw new AssertionError(message + " (no exception thrown)");
        }

        public boolean summary() {
            System.out.println("Tests: " + passed + " passed, " + failed + " failed");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
            return failed == 0;
        }
    }

    private static Library freshLibrary() {
        IdGenerator.reset();
        Library lib = new Library(LocalDate.of(2026, 1, 5));
        SampleData.load(lib);
        return lib;
    }

    /** Runs the unit tests. Returns true when all of them pass. */
    public static boolean runTests() {
        TestRunner t = new TestRunner();
        System.out.println("Running tests...");

        t.run("ISBN validation accepts a valid number", () ->
                TestRunner.assertEquals("9780306406157", Validator.requireIsbn13("978-0-306-40615-7"), "isbn"));

        t.run("ISBN validation rejects a bad check digit", () ->
                TestRunner.assertThrows(ValidationException.class,
                        () -> Validator.requireIsbn13("978-0-306-40615-8"), "bad isbn"));

        t.run("Email validation", () -> {
            TestRunner.assertEquals("a@b.com", Validator.requireEmail("A@B.com"), "lowercase");
            TestRunner.assertThrows(ValidationException.class, () -> Validator.requireEmail("nope"), "invalid email");
        });

        t.run("Money rounds half up", () ->
                TestRunner.assertEquals("$1.01", Money.format(new BigDecimal("1.005")), "rounding"));

        t.run("Trie autocomplete", () -> {
            Trie trie = new Trie();
            trie.insert("Dragon");
            trie.insert("Dragons of Fire");
            trie.insert("Dune");
            TestRunner.assertEquals(2, trie.suggestions("dra", 10).size(), "two matches");
            TestRunner.assertEquals(0, trie.suggestions("x", 10).size(), "no matches");
        });

        t.run("LRU cache evicts the oldest entry", () -> {
            LruCache<String, Integer> cache = new LruCache<>(2);
            cache.put("a", 1);
            cache.put("b", 2);
            cache.get("a");
            cache.put("c", 3);
            TestRunner.check(cache.containsKey("a") && !cache.containsKey("b"), "b should be evicted");
        });

        t.run("Catalog search ranks title matches first", () -> {
            Library lib = freshLibrary();
            List<Book> found = lib.catalog.search("dragons");
            TestRunner.check(!found.isEmpty(), "results expected");
            TestRunner.assertEquals("Dragons of the Ember Coast", found.get(0).getTitle(), "top result");
        });

        t.run("Duplicate ISBN is rejected", () -> {
            Library lib = freshLibrary();
            TestRunner.assertThrows(ValidationException.class, () ->
                    lib.catalog.addBook("9780306406157", "Copy", "Someone", Genre.FICTION, 2000, "5.00", 1),
                    "duplicate isbn");
        });

        t.run("Borrowing reduces availability", () -> {
            Library lib = freshLibrary();
            int before = lib.catalog.availableCount("B-0002");
            lib.loans.borrow("M-0001", "B-0002");
            TestRunner.assertEquals(before - 1, lib.catalog.availableCount("B-0002"), "available copies");
        });

        t.run("Loan limit is enforced", () -> {
            Library lib = freshLibrary();
            lib.loans.borrow("M-0001", "B-0001");
            lib.loans.borrow("M-0001", "B-0002");
            lib.loans.borrow("M-0001", "B-0004");
            TestRunner.assertThrows(LoanLimitException.class,
                    () -> lib.loans.borrow("M-0001", "B-0005"), "student limit is 3");
        });

        t.run("Same title cannot be borrowed twice at once", () -> {
            Library lib = freshLibrary();
            lib.loans.borrow("M-0002", "B-0001");
            TestRunner.assertThrows(ValidationException.class,
                    () -> lib.loans.borrow("M-0002", "B-0001"), "duplicate title");
        });

        t.run("Unavailable title raises an error", () -> {
            Library lib = freshLibrary();
            lib.loans.borrow("M-0001", "B-0003");
            TestRunner.assertThrows(ItemUnavailableException.class,
                    () -> lib.loans.borrow("M-0002", "B-0003"), "only one copy");
        });

        t.run("Late return creates a fine", () -> {
            Library lib = freshLibrary();
            Loan loan = lib.loans.borrow("M-0001", "B-0001");
            lib.advance(14 + 4);
            lib.loans.returnCopy(loan.getCopyId(), 5);
            TestRunner.assertEquals(Money.of("1.00"), lib.fines.outstanding("M-0001"), "4 days at 0.25");
        });

        t.run("Fine is capped at the book price", () -> {
            Library lib = freshLibrary();
            Loan loan = lib.loans.borrow("M-0002", "B-0006");
            lib.advance(21 + 200);
            lib.loans.returnCopy(loan.getCopyId(), 5);
            TestRunner.assertEquals(Money.of("11.25"), lib.fines.outstanding("M-0002"), "capped at price");
        });

        t.run("Renewal extends the due date", () -> {
            Library lib = freshLibrary();
            Loan loan = lib.loans.borrow("M-0003", "B-0004");
            LocalDate before = loan.getDueDate();
            lib.loans.renew(loan.getId());
            TestRunner.assertEquals(before.plusDays(30), loan.getDueDate(), "due date");
        });

        t.run("Renewal limit", () -> {
            Library lib = freshLibrary();
            Loan loan = lib.loans.borrow("M-0003", "B-0004");
            lib.loans.renew(loan.getId());
            lib.loans.renew(loan.getId());
            TestRunner.assertThrows(ValidationException.class,
                    () -> lib.loans.renew(loan.getId()), "third renewal");
        });

        t.run("Overdue loans cannot be renewed", () -> {
            Library lib = freshLibrary();
            Loan loan = lib.loans.borrow("M-0001", "B-0004");
            lib.advance(20);
            TestRunner.assertThrows(ValidationException.class,
                    () -> lib.loans.renew(loan.getId()), "overdue renewal");
        });

        t.run("Reservation holds the returned copy", () -> {
            Library lib = freshLibrary();
            Loan loan = lib.loans.borrow("M-0001", "B-0003");
            lib.reservations.reserve(lib.members.require("M-0002"), lib.catalog.requireBook("B-0003"));
            lib.loans.returnCopy(loan.getCopyId(), 5);
            TestRunner.assertEquals(CopyStatus.RESERVED, lib.catalog.requireCopy(loan.getCopyId()).getStatus(), "held");
            Loan next = lib.loans.borrow("M-0002", "B-0003");
            TestRunner.assertEquals(loan.getCopyId(), next.getCopyId(), "same copy goes to the reserver");
        });

        t.run("Uncollected hold expires and passes on", () -> {
            Library lib = freshLibrary();
            Loan loan = lib.loans.borrow("M-0001", "B-0003");
            lib.reservations.reserve(lib.members.require("M-0002"), lib.catalog.requireBook("B-0003"));
            lib.reservations.reserve(lib.members.require("M-0003"), lib.catalog.requireBook("B-0003"));
            lib.loans.returnCopy(loan.getCopyId(), 5);
            lib.advance(ReservationService.HOLD_DAYS + 1);
            Optional<Reservation> ready = lib.reservations.readyFor("M-0003", "B-0003");
            TestRunner.check(ready.isPresent(), "hold should pass to the next member");
        });

        t.run("Damaged copy is not lent again", () -> {
            Library lib = freshLibrary();
            Loan loan = lib.loans.borrow("M-0001", "B-0003");
            lib.loans.returnCopy(loan.getCopyId(), 1);
            TestRunner.assertEquals(CopyStatus.DAMAGED, lib.catalog.requireCopy(loan.getCopyId()).getStatus(), "damaged");
            TestRunner.assertEquals(0, lib.catalog.availableCount("B-0003"), "none available");
        });

        t.run("Lost item charges price plus handling fee", () -> {
            Library lib = freshLibrary();
            Loan loan = lib.loans.borrow("M-0004", "B-0006");
            lib.loans.markLost(loan.getId());
            TestRunner.assertEquals(Money.of("16.25"), lib.fines.outstanding("M-0004"), "11.25 + 5.00");
        });

        t.run("Large debt blocks borrowing, payment unblocks", () -> {
            Library lib = freshLibrary();
            Loan loan = lib.loans.borrow("M-0004", "B-0002");
            lib.loans.markLost(loan.getId());
            TestRunner.check(lib.members.require("M-0004").isBlocked(), "should be blocked");
            TestRunner.assertThrows(MemberBlockedException.class,
                    () -> lib.loans.borrow("M-0004", "B-0001"), "blocked borrow");
            lib.fines.pay("M-0004", Money.of("60.00"));
            TestRunner.check(!lib.members.require("M-0004").isBlocked(), "should be unblocked");
        });

        t.run("Payments go to the oldest fine first", () -> {
            Library lib = freshLibrary();
            lib.fines.issue("M-0001", "L-X", Money.of("2.00"), "first");
            lib.advance(1);
            lib.fines.issue("M-0001", "L-Y", Money.of("3.00"), "second");
            lib.fines.pay("M-0001", Money.of("2.50"));
            TestRunner.assertEquals(Money.of("2.50"), lib.fines.outstanding("M-0001"), "remaining");
            TestRunner.assertEquals(1, lib.fines.unpaidFor("M-0001").size(), "one fine left");
        });

        t.run("Waived fines do not count", () -> {
            Library lib = freshLibrary();
            Fine fine = lib.fines.issue("M-0002", "L-X", Money.of("4.00"), "test");
            lib.fines.waive(fine.getId());
            TestRunner.assertEquals(Money.ZERO, lib.fines.outstanding("M-0002"), "waived");
        });

        t.run("Recommendations follow the member's taste", () -> {
            Library lib = freshLibrary();
            Loan loan = lib.loans.borrow("M-0002", "B-0004");
            lib.loans.returnCopy(loan.getCopyId(), 5);
            List<Book> recs = lib.recommender.recommend("M-0002", 3);
            TestRunner.assertEquals("Dragons of the Ember Coast", recs.get(0).getTitle(), "same genre");
        });

        t.run("Command interpreter handles errors gracefully", () -> {
            Library lib = freshLibrary();
            CommandInterpreter cli = new CommandInterpreter(lib);
            TestRunner.check(cli.execute("borrow M-9999 B-0001").startsWith("ERROR"), "unknown member");
            TestRunner.check(cli.execute("borrow M-0001").startsWith("ERROR"), "missing argument");
            TestRunner.check(cli.execute("frobnicate").startsWith("Unknown command"), "unknown command");
        });

        t.run("Audit log records events", () -> {
            Library lib = freshLibrary();
            lib.loans.borrow("M-0001", "B-0001");
            TestRunner.assertEquals(1L, lib.audit.count(EventType.LOAN_CREATED), "one loan event");
            TestRunner.assertEquals(4L, lib.audit.count(EventType.MEMBER_REGISTERED), "four members");
        });

        return t.summary();
    }

    // =====================================================================
    // SECTION 12: DEMONSTRATION
    // =====================================================================

    private static void heading(String text) {
        System.out.println();
        System.out.println("=== " + text + " ===");
    }

    private static void runDemo() {
        IdGenerator.reset();
        Library lib = new Library(LocalDate.of(2026, 1, 5));
        SampleData.load(lib);
        CommandInterpreter cli = new CommandInterpreter(lib);

        String[] script = {
                "help",
                "search dragons",
                "suggest tob",
                "borrow M-0001 B-0001",
                "borrow M-0001 B-0002",
                "borrow M-0002 B-0003",
                "reserve M-0003 B-0003",
                "borrow M-0003 B-0004",
                "borrow M-0004 B-0006",
                "renew L-0003",
                "advance 10",
                "return C-0006 5",
                "borrow M-0003 B-0003",
                "advance 10",
                "report overdue",
                "return C-0001 4",
                "advance 3",
                "lost L-0002",
                "report members",
                "pay M-0001 5.00",
                "report finance",
                "recommend M-0003",
                "report popular",
                "report genre",
                "report inventory"
        };

        heading("Scripted session starting " + lib.clock.today());
        for (String line : script) {
            System.out.println();
            System.out.println("> " + line);
            System.out.println(cli.execute(line));
        }

        heading("Last audit log entries");
        for (Event e : lib.audit.last(8)) {
            System.out.println("  " + e);
        }

        heading("Loans exported as CSV");
        System.out.print(lib.reports.loansCsv());
    }

    public static void main(String[] args) {
        boolean ok = runTests();
        if (!ok) {
            System.out.println("Some tests failed; skipping the demo.");
            System.exit(1);
        }
        runDemo();
    }
}
