package com.hingebot;

import java.util.List;

public class Errors {
    public static class HingebotException extends RuntimeException {
        public HingebotException(String message) {
            super(message);
        }

        public HingebotException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class ConfigValidationException extends HingebotException {
        private final List<String> errors;

        public ConfigValidationException(List<String> errors) {
            super("Config validation failed:\n  " + String.join("\n  ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> getErrors() {
            return errors;
        }
    }

    public static class AdbException extends HingebotException {
        public AdbException(String message) {
            super(message);
        }

        public AdbException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class PreflightException extends HingebotException {
        public PreflightException(String message) {
            super(message);
        }
    }

    public static class AppLostException extends HingebotException {
        public AppLostException(String message) {
            super(message);
        }
    }

    public static class TargetNotFoundException extends HingebotException {
        public TargetNotFoundException(String message) {
            super(message);
        }
    }

    public static class ActionFailedException extends HingebotException {
        public ActionFailedException(String message) {
            super(message);
        }
    }

    public static class OutOfLikesException extends HingebotException {
        public OutOfLikesException(String message) {
            super(message);
        }
    }
}
