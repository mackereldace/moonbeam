package com.limelight.preferences;

import android.content.Context;
import android.preference.EditTextPreference;
import android.text.InputType;
import android.util.AttributeSet;
import android.widget.EditText;

import com.limelight.R;

/**
 * An EditText preference for a secret.
 *
 * Two rules: the stored value is never shown - not in the dialog, not in the summary - and an
 * empty dialog means "leave it alone" rather than "clear it", because a user who opens the dialog
 * to look and then dismisses it should not silently lose their token.
 */
public class SecretPreference extends EditTextPreference {

    public SecretPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SecretPreference(Context context) {
        super(context);
    }

    @Override
    public CharSequence getSummary() {
        boolean hasValue = getPersistedString("").trim().length() > 0;
        return getContext().getString(hasValue
                ? R.string.summary_secret_set : R.string.summary_secret_unset);
    }

    @Override
    protected void onBindDialogView(android.view.View view) {
        // Start empty and masked; whatever the stored value is stays out of sight.
        EditText editText = getEditText();
        if (editText != null) {
            editText.setText("");
            boolean hasValue = getPersistedString("").trim().length() > 0;
            editText.setHint(getContext().getString(hasValue
                    ? R.string.summary_secret_keep : R.string.summary_secret_unset));
            editText.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        super.onBindDialogView(view);
        if (editText != null) {
            editText.setText("");
        }
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        if (positiveResult) {
            EditText editText = getEditText();
            String value = editText == null ? "" : editText.getText().toString().trim();
            if (value.length() > 0) {
                persistString(value);
            }
        }
        notifyDependencyChange(shouldDisableDependents());
        notifyChanged();
    }
}
