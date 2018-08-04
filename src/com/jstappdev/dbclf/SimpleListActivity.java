package com.jstappdev.dbclf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ExpandableListAdapter;
import android.widget.ExpandableListView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class SimpleListActivity extends Activity {

    private ExpandableListView expListView;
    private List<String> listDataHeader;
    private HashMap<String, String> listDataChild;
    private Boolean showRecogsOnly;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);

        Intent intent = getIntent();
        showRecogsOnly = intent.getBooleanExtra("SHOW_RECOGS", false);

        expListView = findViewById(R.id.lvExp);

        prepareListData();

        ExpandableListAdapter listAdapter = new ListAdapter(this, listDataHeader, listDataChild);

        expListView.setOnChildClickListener((parent, v, groupPosition, childPosition, id) -> {
            final String title = listDataHeader.get(groupPosition);
            final String searchText = title.replace(" ", "+");

            DialogInterface.OnClickListener dialogClickListener = (dialog, which) -> {
                switch (which) {
                    case DialogInterface.BUTTON_POSITIVE:
                        final String url = "https://wikipedia.org/w/index.php?search="
                                + searchText + "&title=Special:Search";

                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setData(Uri.parse(url));
                        startActivity(i);
                        break;

                    case DialogInterface.BUTTON_NEGATIVE:
                        break;
                }
            };

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage(R.string.searchFor).setTitle(title)
                    .setNegativeButton(R.string.no, dialogClickListener)
                    .setPositiveButton(R.string.yes, dialogClickListener).show();

            return false;
        });

        expListView.setAdapter(listAdapter);
    }

    @Override
    public void onResume() {
        super.onResume();

        if (showRecogsOnly)
            for (int i = 0; i < listDataHeader.size(); i++)
                expListView.expandGroup(i);
    }

    /*
     * Preparing the list data
     */
    private void prepareListData() {
        listDataHeader = new ArrayList<>();
        listDataChild = new HashMap<>();

        if (showRecogsOnly) {
            int i = 0;
            for (String r : CameraActivity.currentRecognitions) {
                listDataHeader.add(r);
                listDataChild.put(listDataHeader.get(i++), r.toLowerCase().replace(" ", "_"));
            }
            return;
        }

        final String actualFilename = ClassifierActivity.LABEL_FILE.split("file:///android_asset/")[1];

        try (BufferedReader br =
                     new BufferedReader(new InputStreamReader(getAssets().open(actualFilename)))) {
            String line;
            int i = 0;
            while ((line = br.readLine()) != null) {
                listDataHeader.add(line);
                listDataChild.put(listDataHeader.get(i++), line.toLowerCase().replace(" ", "_"));
            }
        } catch (IOException e) {
            throw new RuntimeException("Problem reading label file!", e);
        }

    }

}
