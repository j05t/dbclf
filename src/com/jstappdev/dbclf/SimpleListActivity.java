package com.jstappdev.dbclf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.ExpandableListAdapter;
import android.widget.ExpandableListView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class SimpleListActivity extends Activity {

    private static String wikiLangSubDomain = "";
    private ExpandableListView expListView;
    private List<String> listDataHeader;
    private HashMap<String, String> listDataChild;
    private ArrayList<String> recogs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_list);

        final String lang = CameraActivity.preferredLanguageCode;
        if (CameraActivity.supportedLanguageCodes.contains(lang)) {
            wikiLangSubDomain = lang + ".";
        }

        recogs = getIntent().getStringArrayListExtra("recogs");

        expListView = findViewById(R.id.lvExp);

        prepareListData();

        final ExpandableListAdapter listAdapter = new ListAdapter(this, listDataHeader, listDataChild);

        expListView.setOnChildClickListener((parent, v, groupPosition, childPosition, id) -> {
            final String title = listDataHeader.get(groupPosition);
            final String searchText = title.replace(" ", "+");

            final DialogInterface.OnClickListener dialogClickListener = (dialog, which) -> {
                switch (which) {
                    case DialogInterface.BUTTON_POSITIVE:
                        final String url = String.format("https://%swikipedia.org/w/index.php?search=%s&title=Special:Search", wikiLangSubDomain, searchText);

                        final Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setData(Uri.parse(url));
                        startActivity(i);
                        break;

                    case DialogInterface.BUTTON_NEGATIVE:
                        break;
                }
            };

            (new AlertDialog.Builder(this))
                    .setMessage(R.string.searchFor).setTitle(title)
                    .setNegativeButton(R.string.no, dialogClickListener)
                    .setPositiveButton(R.string.yes, dialogClickListener).show();

            return false;
        });

        expListView.setFastScrollEnabled(true);
        expListView.setAdapter(listAdapter);
    }

    @Override
    public void onResume() {
        super.onResume();

        if (null != recogs)
            for (int i = 0; i < listDataHeader.size(); i++)
                expListView.expandGroup(i);
    }

    /*
     * Preparing the list data
     */
    private void prepareListData() {
        listDataHeader = new ArrayList<>();
        listDataChild = new HashMap<>();

        Collections.addAll(listDataHeader, getResources().getStringArray(R.array.breeds_array));
        final String[] fileNames = getResources().getStringArray(R.array.file_names);

        // load file names
        for (int i = 0; i < listDataHeader.size(); i++) {
            listDataChild.put(listDataHeader.get(i), fileNames[i]);
        }

        if (null != recogs) {
            listDataHeader = new ArrayList<>();
            listDataHeader.addAll(recogs);
            expListView.setFastScrollAlwaysVisible(false);
        } else {
            final Collator coll = Collator.getInstance(Locale.getDefault());
            coll.setStrength(Collator.PRIMARY);
            Collections.sort(listDataHeader, coll);
            expListView.setFastScrollAlwaysVisible(true);
        }
    }

}
