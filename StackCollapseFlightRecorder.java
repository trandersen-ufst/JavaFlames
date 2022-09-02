import com.sun.net.httpserver.HttpServer;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordingFile;

import java.awt.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class StackCollapseFlightRecorder {

    private static final int HTTP_PORT = 8090;
    private static final String PATH_TO_DATA = "data";

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            exit(1, "expected jfr input file as argument");
        }
        var jfrFile = Paths.get(args[0]);
        if (!Files.exists(jfrFile)) {
            exit(2, jfrFile + " not found.");
        }
        for(var line : produceFlameGraphLog(jfrFile).collect(Collectors.toList())) {
            System.out.print(line + "\n"); // Ensure Unix endings.
        }
    }

    private static void exit(int code, String message) {
        System.err.println(message);
        System.exit(code);
    }


    public static Stream<String> produceFlameGraphLog(final Path jfrRecording) throws IOException {
        var recordingFile = new RecordingFile(jfrRecording);
        return extractEvents(recordingFile)
                .filter(it -> "jdk.ExecutionSample".equals(it.getEventType().getName()))
                .map(event -> collapseFrames(event.getStackTrace().getFrames()))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream().map(e -> "%s %d".formatted(e.getKey(), e.getValue()))
                .sorted()
                .onClose(io(recordingFile::close));
    }

    private static String collapseFrames(List<RecordedFrame> frames) {
        var methodNames = new ArrayDeque<String>(frames.size());
        for (var frame : frames) {
            final RecordedMethod method = frame.getMethod();
            methodNames.addFirst("%s::%s".formatted(method.getType().getName(), method.getName()));
        }
        return String.join(";", methodNames);
    }

    private static Stream<RecordedEvent> extractEvents(RecordingFile recordingFile) {
        return Stream.generate(() -> 
            recordingFile.hasMoreEvents() ? 
                io(recordingFile::readEvent).get() : 
                null
        ).takeWhile(Objects::nonNull);
    }

    // Helpers for dealing with checked IOException's in lambdas

    @FunctionalInterface
    interface IORunnable {
        void run() throws IOException;
    }

    @FunctionalInterface
    interface IOConsumer<T> {
        void apply(T input) throws IOException;
    }

    @FunctionalInterface
    interface IOSupplier<T> {
        T get() throws IOException;
    }

    private static <T> Supplier<T> io(IOSupplier<T> supplier) {
        return () -> {
            try {
                return supplier.get();
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private static <T> Consumer<T> io(IOConsumer<T> consumer) {
        return t -> {
            try {
                consumer.apply(t);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private static Runnable io(IORunnable runnable) {
        return () -> {
            try {
                runnable.run();
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }
}
