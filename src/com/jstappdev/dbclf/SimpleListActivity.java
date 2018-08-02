package com.jstappdev.dbclf;

import android.app.Activity;
import android.content.res.AssetManager;
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

    ExpandableListAdapter listAdapter;
    ExpandableListView expListView;
    List<String> listDataHeader;
    HashMap<String, String> listDataChild;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);

        expListView =  findViewById(R.id.lvExp);

        prepareListData();

        listAdapter = new ListAdapter(this, listDataHeader, listDataChild);

        expListView.setAdapter(listAdapter);
    }

    /*
     * Preparing the list data
     */
    private void prepareListData() {
        listDataHeader = new ArrayList<>();
        listDataChild = new HashMap<>();

        final String actualFilename = ClassifierActivity.LABEL_FILE.split("file:///android_asset/")[1];

        AssetManager assetManager = getAssets();

        try (BufferedReader br =
                     new BufferedReader(new InputStreamReader(assetManager.open(actualFilename)))) {
            String line;
            int i = 0;

            while ((line = br.readLine()) != null) {
                listDataHeader.add(line);
                listDataChild.put(listDataHeader.get(i), line.toLowerCase().replace(" ", "_"));
                i++;
            }
        } catch (IOException e) {
            throw new RuntimeException("Problem reading label file!", e);
        }

    }

}
