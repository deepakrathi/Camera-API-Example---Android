package com.example.deepak.camera2;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.ByteArrayOutputStream;

public class UploadActivity extends AppCompatActivity {

    Bitmap image = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.upload_activity_layout);

        ImageView imageView = findViewById(R.id.imageView);

        Uri uri = getIntent().getParcelableExtra("image");
        try {
            image = BitmapFactory.decodeStream(getContentResolver().openInputStream(uri));
            imageView.setImageBitmap(image);
            imageView.setDrawingCacheEnabled(true);
            imageView.buildDrawingCache();
        } catch (Exception e) {
            UtilsFunctions.Companion.eLog("exception here", e.toString());
        }

        Button button = findViewById(R.id.uploadBTN);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UtilsFunctions.Companion.toast(UploadActivity.this, "Uploading image Please wait...");
                FirebaseStorage storage = FirebaseStorage.getInstance();
                StorageReference storageRef = storage.getReference();
                StorageReference imageRef = storageRef.child("camera2.jpg");
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                image.compress(Bitmap.CompressFormat.JPEG, 100, baos);
                byte[] data = baos.toByteArray();
                UploadTask uploadTask = imageRef.putBytes(data);
                uploadTask.addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception exception) {
                        UtilsFunctions.Companion.eLog("image uploaded", "false");
                        UtilsFunctions.Companion.toast(UploadActivity.this, "Upload Failed");
                    }
                }).addOnSuccessListener(new OnSuccessListener<UploadTask.TaskSnapshot>() {
                    @Override
                    public void onSuccess(UploadTask.TaskSnapshot taskSnapshot) {
                        UtilsFunctions.Companion.eLog("image uploaded", "true");
                        UtilsFunctions.Companion.toast(UploadActivity.this, "Image Uploaded");
                        finish();
                    }
                });

            }
        });

    }
}
