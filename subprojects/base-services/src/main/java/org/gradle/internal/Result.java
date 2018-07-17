/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal;

import com.google.common.base.Preconditions;

import java.text.MessageFormat;

/**
 * A general purpose result object that either represents a non-null value or an error with a message.
 *
 * @param <T> the value type
 */
public abstract class Result<T> {

    public static <T> Result<T> value(T value) {
        return new Value<T>(value);
    }

    public static <T> Result<T> error(String messageTemplate, Object... arguments) {
        return new Error<T>(messageTemplate, arguments);
    }

    private Result() {
    }

    public abstract boolean hasValue();

    public abstract T getValue();

    public abstract String getError();

    private static class Value<T> extends Result<T> {

        private final T value;

        public Value(T value) {
            this.value = Preconditions.checkNotNull(value, "value must not be null");
        }

        @Override
        public boolean hasValue() {
            return true;
        }

        @Override
        public T getValue() {
            return value;
        }

        @Override
        public String getError() {
            throw new IllegalStateException("Error messages are only available for errors");
        }
    }

    private static class Error<T> extends Result<T> {

        private final String messageTemplate;
        private final Object[] arguments;

        public Error(String messageTemplate, Object[] arguments) {
            this.messageTemplate = Preconditions.checkNotNull(messageTemplate, "messageTemplate must not be null");
            this.arguments = Preconditions.checkNotNull(arguments, "arguments must not be null");
        }

        @Override
        public boolean hasValue() {
            return false;
        }

        @Override
        public T getValue() {
            throw new IllegalStateException(getError());
        }

        @Override
        public String getError() {
            return MessageFormat.format(messageTemplate, arguments);
        }
    }
}
