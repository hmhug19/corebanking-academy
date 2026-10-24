package vn.com.coreacademy.assistant.tool;

import java.util.function.BiConsumer;
import java.util.function.Function;

import vn.com.coreacademy.assistant.dto.PendingActionView;
import vn.com.coreacademy.identity.dto.ProfileUpdateRequest;
import vn.com.coreacademy.identity.dto.UserInfo;

/** One proposed change of the user's own profile; {@code null} means the value is (or becomes) empty. */
record ProfileChange(Field field, String from, String to) {
    /** The profile fields the assistant may change, with how to read and write each. */
    enum Field {
        FULL_NAME("fullName", "Họ tên", UserInfo::fullName, ProfileUpdateRequest::setFullName),
        EMAIL("email", "Email", UserInfo::email, ProfileUpdateRequest::setEmail),
        POSITION_NAME("positionName", "Chức danh", UserInfo::positionName, ProfileUpdateRequest::setPositionName);

        private final String json;
        private final String label;
        private final Function<UserInfo, String> reader;
        private final BiConsumer<ProfileUpdateRequest, String> writer;

        Field(String json, String label, Function<UserInfo, String> reader,
              BiConsumer<ProfileUpdateRequest, String> writer) {
            this.json = json;
            this.label = label;
            this.reader = reader;
            this.writer = writer;
        }

        String json() {
            return json;
        }

        String label() {
            return label;
        }

        /** The current value, empty text counting as no value. */
        String current(UserInfo profile) {
            return blankToNull(reader.apply(profile));
        }

        void apply(ProfileUpdateRequest request, String value) {
            writer.accept(request, value);
        }

        static Field of(String json) {
            for (Field field : values()) {
                if (field.json.equals(json)) {
                    return field;
                }
            }
            throw new IllegalArgumentException("Unknown profile field");
        }
    }

    PendingActionView.Change toView() {
        return new PendingActionView.Change(field.json(), field.label(), from, to);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
