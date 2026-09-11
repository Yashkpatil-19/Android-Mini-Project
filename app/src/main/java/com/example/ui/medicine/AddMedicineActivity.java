package com.example.ui.medicine;

import android.Manifest;
import android.app.TimePickerDialog;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.MyTherapyApp;
import com.example.R;
import com.example.data.model.Medicine;
import com.example.data.repository.MedicineRepository;
import com.example.databinding.ActivityAddMedicineBinding;
import com.example.databinding.ItemCustomDoseTimeBinding;
import com.example.util.MedicineReminderScheduler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

/**
 * Activity allowing users to add a new medication entry with a photo (gallery or camera),
 * frequency dropdown, time slot checkboxes, timing relation radio buttons, and exact notification time.
 * On save, schedules an exact local alarm with AlarmManager and persists to Room database.
 */
public class AddMedicineActivity extends AppCompatActivity {

    private static final String TAG = "AddMedicineActivity";
    public static final String EXTRA_USER_ID = "extra_user_id";
    public static final String EXTRA_USER_NAME = "extra_user_name";

    @Inject
    MedicineRepository medicineRepository;

    private ActivityAddMedicineBinding binding;
    private long currentUserId = -1;
    private String currentUsername = "";
    private String selectedPhotoPath = null;

    private static class DoseTimeEntry {
        String label;
        int hour;
        int minute;

        DoseTimeEntry(String label, int hour, int minute) {
            this.label = label;
            this.hour = hour;
            this.minute = minute;
        }

        String getFormattedTime() {
            String amPm = hour >= 12 ? "PM" : "AM";
            int formattedHour = hour % 12;
            if (formattedHour == 0) formattedHour = 12;
            return String.format(Locale.getDefault(), "%02d:%02d %s", formattedHour, minute, amPm);
        }
    }

    private final List<DoseTimeEntry> doseTimeEntries = new ArrayList<>();

    // Frequency options for dropdown
    private static final String[] FREQUENCY_OPTIONS = new String[]{
            "1 time a day",
            "2 times a day",
            "3 times a day",
            "4 times a day",
            "5 times a day",
            "6 times a day"
    };

    // ActivityResultLauncher for Android Photo Picker
    private final ActivityResultLauncher<PickVisualMediaRequest> pickMediaLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    saveGalleryImageLocally(uri);
                }
            });

    // ActivityResultLauncher for Camera capture intent
    private final ActivityResultLauncher<Void> takePhotoLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicturePreview(), bitmap -> {
                if (bitmap != null) {
                    saveCameraBitmapLocally(bitmap);
                }
            });

    // Permission launcher for Android 13+ POST_NOTIFICATIONS
    private final ActivityResultLauncher<String> requestNotificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (!isGranted) {
                    Toast.makeText(this, "Notifications permission denied. Reminders may not appear in system bar.", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAddMedicineBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Perform Dagger injection with fallback
        try {
            MyTherapyApp.getInstance().getComponent().inject(this);
        } catch (Throwable ignored) {
        }
        if (medicineRepository == null) {
            medicineRepository = MyTherapyApp.getInstance().getMedicineRepository();
        }

        if (getIntent() != null) {
            currentUserId = getIntent().getLongExtra(EXTRA_USER_ID, -1);
            currentUsername = getIntent().getStringExtra(EXTRA_USER_NAME);
            if (currentUsername == null) {
                currentUsername = "";
            }
        }

        checkNotificationPermission();

        setupToolbar();
        setupPhotoOptions();
        setupFrequencyDropdown();
        setupTimeSlotsAndSeparateTimes();
        setupSaveButton();
    }

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void setupToolbar() {
        binding.toolbarAddMedicine.setNavigationOnClickListener(v -> finish());
    }

    private void setupPhotoOptions() {
        // 1. Image picker from Gallery
        binding.btnSelectPhoto.setOnClickListener(v -> {
            pickMediaLauncher.launch(
                    new PickVisualMediaRequest.Builder()
                            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                            .build()
            );
        });

        // 2. Camera intent option to capture photo
        binding.btnTakePhoto.setOnClickListener(v -> {
            try {
                takePhotoLauncher.launch(null);
            } catch (Exception e) {
                Toast.makeText(this, "Unable to launch camera: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void saveGalleryImageLocally(@NonNull Uri sourceUri) {
        try {
            InputStream is = getContentResolver().openInputStream(sourceUri);
            if (is != null) {
                File storageDir = new File(getFilesDir(), "medicines");
                if (!storageDir.exists()) {
                    storageDir.mkdirs();
                }

                File destFile = new File(storageDir, "med_" + System.currentTimeMillis() + ".jpg");
                FileOutputStream fos = new FileOutputStream(destFile);

                byte[] buffer = new byte[4096];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
                fos.flush();
                fos.close();
                is.close();

                selectedPhotoPath = destFile.getAbsolutePath();

                Bitmap bitmap = BitmapFactory.decodeFile(destFile.getAbsolutePath());
                if (bitmap != null) {
                    binding.ivPhotoPreview.setImageBitmap(bitmap);
                }
                Toast.makeText(this, "Photo attached successfully", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save gallery image: " + e.getMessage(), e);
            Toast.makeText(this, "Could not load image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveCameraBitmapLocally(@NonNull Bitmap bitmap) {
        try {
            File storageDir = new File(getFilesDir(), "medicines");
            if (!storageDir.exists()) {
                storageDir.mkdirs();
            }

            File destFile = new File(storageDir, "med_cam_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream fos = new FileOutputStream(destFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            fos.flush();
            fos.close();

            selectedPhotoPath = destFile.getAbsolutePath();
            binding.ivPhotoPreview.setImageBitmap(bitmap);
            Toast.makeText(this, "Camera photo captured and attached", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save camera bitmap: " + e.getMessage(), e);
            Toast.makeText(this, "Could not save photo: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void setupFrequencyDropdown() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                FREQUENCY_OPTIONS
        );
        binding.actvFrequencyDropdown.setAdapter(adapter);
        binding.actvFrequencyDropdown.setText(FREQUENCY_OPTIONS[0], false);
    }

    private int getSelectedFrequency() {
        String selected = binding.actvFrequencyDropdown.getText() != null
                ? binding.actvFrequencyDropdown.getText().toString() : "";
        for (int i = 0; i < FREQUENCY_OPTIONS.length; i++) {
            if (FREQUENCY_OPTIONS[i].equalsIgnoreCase(selected)) {
                return i + 1;
            }
        }
        // Fallback parse first digit
        if (!TextUtils.isEmpty(selected)) {
            char firstChar = selected.charAt(0);
            if (Character.isDigit(firstChar)) {
                return Character.getNumericValue(firstChar);
            }
        }
        return 1;
    }

    private void setupTimeSlotsAndSeparateTimes() {
        // Initialize default entry for Morning
        doseTimeEntries.clear();
        doseTimeEntries.add(new DoseTimeEntry("Morning", 8, 0));

        // Listen for checkbox changes to automatically sync default slots
        binding.cbSlotMorning.setOnCheckedChangeListener((buttonView, isChecked) -> onSlotCheckboxChanged("Morning", isChecked, 8, 0));
        binding.cbSlotAfternoon.setOnCheckedChangeListener((buttonView, isChecked) -> onSlotCheckboxChanged("Afternoon", isChecked, 13, 0));
        binding.cbSlotNight.setOnCheckedChangeListener((buttonView, isChecked) -> onSlotCheckboxChanged("Night", isChecked, 20, 0));

        // Add custom time button
        binding.btnAddTimeSlot.setOnClickListener(v -> {
            int currentCount = doseTimeEntries.size();
            String customLabel = "Dose " + (currentCount + 1);
            doseTimeEntries.add(new DoseTimeEntry(customLabel, 12, 0));
            renderDoseTimeList();
        });

        renderDoseTimeList();
    }

    private void onSlotCheckboxChanged(String slotName, boolean isChecked, int defaultHour, int defaultMinute) {
        if (isChecked) {
            // Check if already present
            boolean found = false;
            for (DoseTimeEntry entry : doseTimeEntries) {
                if (entry.label.equalsIgnoreCase(slotName)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                doseTimeEntries.add(new DoseTimeEntry(slotName, defaultHour, defaultMinute));
            }
        } else {
            // Remove the slot
            for (int i = 0; i < doseTimeEntries.size(); i++) {
                if (doseTimeEntries.get(i).label.equalsIgnoreCase(slotName)) {
                    doseTimeEntries.remove(i);
                    break;
                }
            }
        }
        renderDoseTimeList();
    }

    private void renderDoseTimeList() {
        binding.containerDoseTimes.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < doseTimeEntries.size(); i++) {
            final int index = i;
            final DoseTimeEntry entry = doseTimeEntries.get(i);

            ItemCustomDoseTimeBinding itemBinding = ItemCustomDoseTimeBinding.inflate(
                    inflater,
                    binding.containerDoseTimes,
                    false
            );

            itemBinding.tvDoseLabel.setText(entry.label + " Dose");
            itemBinding.btnDoseTime.setText(entry.getFormattedTime());

            // On clicking time button, open TimePickerDialog for this specific dose
            itemBinding.btnDoseTime.setOnClickListener(v -> {
                TimePickerDialog dialog = new TimePickerDialog(
                        AddMedicineActivity.this,
                        (view, hourOfDay, minute) -> {
                            entry.hour = hourOfDay;
                            entry.minute = minute;
                            itemBinding.btnDoseTime.setText(entry.getFormattedTime());
                        },
                        entry.hour,
                        entry.minute,
                        false
                );
                dialog.show();
            });

            // If there is more than 1 dose time, allow removing extra entries
            if (doseTimeEntries.size() > 1) {
                itemBinding.btnRemoveDoseTime.setVisibility(View.VISIBLE);
                itemBinding.btnRemoveDoseTime.setOnClickListener(v -> {
                    doseTimeEntries.remove(index);
                    // Also uncheck checkbox if it matches a standard slot
                    if (entry.label.equalsIgnoreCase("Morning")) {
                        binding.cbSlotMorning.setChecked(false);
                    } else if (entry.label.equalsIgnoreCase("Afternoon")) {
                        binding.cbSlotAfternoon.setChecked(false);
                    } else if (entry.label.equalsIgnoreCase("Night")) {
                        binding.cbSlotNight.setChecked(false);
                    }
                    renderDoseTimeList();
                });
            } else {
                itemBinding.btnRemoveDoseTime.setVisibility(View.GONE);
            }

            binding.containerDoseTimes.addView(itemBinding.getRoot());
        }
    }

    private void setupSaveButton() {
        binding.btnSaveMedicine.setOnClickListener(v -> attemptSaveMedicine());
    }

    private void attemptSaveMedicine() {
        binding.tilMedicineName.setError(null);

        // 1. Validate Medicine Name
        String medicineName = binding.etMedicineName.getText() != null
                ? binding.etMedicineName.getText().toString().trim() : "";

        if (TextUtils.isEmpty(medicineName)) {
            binding.tilMedicineName.setError("Please enter the medicine name");
            binding.etMedicineName.requestFocus();
            return;
        }

        // 2. Read Frequency from Dropdown
        int frequency = getSelectedFrequency();

        // 3. Read Time Slots
        List<String> selectedSlots = new ArrayList<>();
        if (binding.cbSlotMorning.isChecked()) {
            selectedSlots.add("Morning");
        }
        if (binding.cbSlotAfternoon.isChecked()) {
            selectedSlots.add("Afternoon");
        }
        if (binding.cbSlotNight.isChecked()) {
            selectedSlots.add("Night");
        }

        // If no checkbox selected but entries exist in dose list, use their labels
        if (selectedSlots.isEmpty()) {
            for (DoseTimeEntry entry : doseTimeEntries) {
                if (!selectedSlots.contains(entry.label)) {
                    selectedSlots.add(entry.label);
                }
            }
        }

        if (selectedSlots.isEmpty() || doseTimeEntries.isEmpty()) {
            Toast.makeText(this, "Please set at least one reminder time", Toast.LENGTH_SHORT).show();
            return;
        }

        String timeSlotsStr = TextUtils.join(", ", selectedSlots);

        // 4. Read Timing Relation from Radio Buttons (Before Eat vs. After Eat)
        String timingRelation = "After Eat";
        if (binding.rbTimingBefore.isChecked()) {
            timingRelation = "Before Eat";
        }

        // 5. Build comma-separated separate reminder times string
        List<String> formattedTimesList = new ArrayList<>();
        for (DoseTimeEntry entry : doseTimeEntries) {
            formattedTimesList.add(entry.getFormattedTime());
        }
        String separateReminderTimesStr = TextUtils.join(", ", formattedTimesList);

        // 6. Construct Medicine Entity
        Medicine newMedicine = new Medicine(
                currentUserId,
                medicineName,
                selectedPhotoPath,
                frequency,
                timeSlotsStr,
                timingRelation,
                separateReminderTimesStr,
                false // starts as Ongoing
        );

        binding.btnSaveMedicine.setEnabled(false);

        // 7. Save to Room database
        medicineRepository.addMedicine(newMedicine, new MedicineRepository.Callback<Medicine>() {
            @Override
            public void onSuccess(Medicine savedMedicine) {
                // 8. Schedule separate local Android Alarms for each customizable dose time
                for (int i = 0; i < doseTimeEntries.size(); i++) {
                    DoseTimeEntry entry = doseTimeEntries.get(i);
                    MedicineReminderScheduler.scheduleReminderWithIndex(
                            AddMedicineActivity.this,
                            savedMedicine,
                            entry.hour,
                            entry.minute,
                            entry.getFormattedTime(),
                            i
                    );
                }

                // 9. Sync to Firebase Cloud Firestore
                if (currentUsername != null && !currentUsername.isEmpty()) {
                    com.example.data.remote.FirebaseSyncManager.getInstance()
                            .syncMedicineToCloud(currentUsername, savedMedicine);
                }

                Toast.makeText(
                        AddMedicineActivity.this,
                        "Reminders scheduled for " + separateReminderTimesStr,
                        Toast.LENGTH_SHORT
                ).show();

                setResult(RESULT_OK);
                finish();
            }

            @Override
            public void onError(@NonNull String errorMessage) {
                binding.btnSaveMedicine.setEnabled(true);
                Toast.makeText(AddMedicineActivity.this, errorMessage, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}
