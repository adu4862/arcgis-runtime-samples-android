/*
 *  Copyright 2019 Esri
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.esri.arcgisruntime.sample.downloadpreplannedmaparea

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.esri.arcgisruntime.concurrent.Job
import com.esri.arcgisruntime.geometry.GeometryEngine
import com.esri.arcgisruntime.loadable.LoadStatus
import com.esri.arcgisruntime.mapping.ArcGISMap
import com.esri.arcgisruntime.mapping.Viewpoint
import com.esri.arcgisruntime.mapping.view.Graphic
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay
import com.esri.arcgisruntime.portal.Portal
import com.esri.arcgisruntime.portal.PortalItem
import com.esri.arcgisruntime.sample.downloadpreplannedmaparea.ProgressDialogFragment.OnProgressDialogDismissListener
import com.esri.arcgisruntime.security.AuthenticationManager
import com.esri.arcgisruntime.security.DefaultAuthenticationChallengeHandler
import com.esri.arcgisruntime.symbology.SimpleLineSymbol
import com.esri.arcgisruntime.symbology.SimpleRenderer
import com.esri.arcgisruntime.tasks.offlinemap.DownloadPreplannedOfflineMapJob
import com.esri.arcgisruntime.tasks.offlinemap.OfflineMapTask
import com.esri.arcgisruntime.tasks.offlinemap.PreplannedMapArea
import com.esri.arcgisruntime.tasks.offlinemap.PreplannedUpdateMode
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.layout_offline_controls.*
import java.io.File
import java.util.ArrayList
import java.util.concurrent.ExecutionException

class MainActivity : AppCompatActivity(), OnProgressDialogDismissListener {

  private var mOfflineMapDirectory: File? = null
  private var mPreplannedAreasListView: ListView? = null
  private var mPreplannedMapAreasAdapter: ArrayAdapter<String>? = null
  private var mDownloadedMapAreasListView: ListView? = null
  private var mDownloadedMapAreaNames: MutableList<String>? = null
  private var mDownloadedMapAreasAdapter: ArrayAdapter<String>? = null
  private val mDownloadedMapAreas: MutableList<ArcGISMap> = ArrayList()

  private var mSelectedPreplannedMapArea: PreplannedMapArea? = null
  private var mDownloadPreplannedOfflineMapJob: DownloadPreplannedOfflineMapJob? = null
  private val areasOfInterestGraphicsOverlay by lazy { GraphicsOverlay() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    // delete any previous instances of downloaded maps
    externalCacheDir?.deleteRecursively()

    // create up a temporary directory in the app's cache for saving downloaded preplanned maps
    File(externalCacheDir?.path + getString(R.string.preplanned_offline_map_dir)).let {
      when {
        it.mkdirs() -> Log.i(TAG, "Created directory for offline map in " + it.path)
        it.exists() -> Log.i(
          TAG,
          "Did not create a new offline map directory, one already exists at " + it.path
        )
        else -> Log.e(TAG, "Error creating offline map directory at: " + it.path)
      }
    }

    // set the authentication manager to handle challenges when accessing the portal
    // Note: The sample data is publicly available, so you shouldn't be challenged
    AuthenticationManager.setAuthenticationChallengeHandler(
      DefaultAuthenticationChallengeHandler(
        this
      )
    )

    // create a portal to ArcGIS Online
    val portal = Portal(getString(R.string.arcgis_online_url))
    // create a portal item using the portal and the item id of a map service
    val portalItem = PortalItem(portal, getString(R.string.naperville_water_network_url))
    // create an offline map task from the portal item
    val offlineMapTask = OfflineMapTask(portalItem)
    // create a map with the portal item
    val onlineMap = ArcGISMap(portalItem)
    // show the map
    mapView.map = onlineMap


    // create a graphics overlay to show the preplanned map areas extents (areas of interest)
    mapView.graphicsOverlays.add(areasOfInterestGraphicsOverlay)
    // create a red outline to mark the areas of interest of the preplanned map areas
    val areaOfInterestLineSymbol =
      SimpleLineSymbol(SimpleLineSymbol.Style.SOLID, Color.RED, 5.0f)
    val areaOfInterestRenderer = SimpleRenderer()
    areaOfInterestRenderer.symbol = areaOfInterestLineSymbol
    areasOfInterestGraphicsOverlay.renderer = areaOfInterestRenderer
    createPreplannedAreasListView(onlineMap, offlineMapTask)
    createDownloadAreasListView()

    // create download button
    downloadButton.apply {
      isEnabled = false
      setOnClickListener { downloadPreplannedArea(offlineMapTask) }
    }
  }

  /**
   * Download the selected preplanned map area from the list view to a temporary directory. The download job is tracked in another list view.
   */
  private fun downloadPreplannedArea(offlineMapTask: OfflineMapTask) {
    if (mSelectedPreplannedMapArea != null) {
      // create default download parameters from the offline map task
      val offlineMapParametersFuture =
        offlineMapTask.createDefaultDownloadPreplannedOfflineMapParametersAsync(
          mSelectedPreplannedMapArea
        )
      offlineMapParametersFuture?.addDoneListener {
        try {
          // get the offline map parameters
          val offlineMapParameters = offlineMapParametersFuture.get()
          // set the update mode to not receive updates
          offlineMapParameters.updateMode = PreplannedUpdateMode.NO_UPDATES
          // create a job to download the preplanned offline map to a temporary directory
          mDownloadPreplannedOfflineMapJob = offlineMapTask.downloadPreplannedOfflineMap(
            offlineMapParameters,
            mOfflineMapDirectory?.path + File.separator + mSelectedPreplannedMapArea?.portalItem?.title
          )
          // start the job
          mDownloadPreplannedOfflineMapJob?.start()

          // show progress dialog for download, includes tracking progress
          showProgressDialog()

          // when the job finishes
          mDownloadPreplannedOfflineMapJob?.addJobDoneListener {
            dismissDialog()
            // if there's a result from the download preplanned offline map job
            if (mDownloadPreplannedOfflineMapJob?.status == Job.Status.SUCCEEDED) {
              val downloadPreplannedOfflineMapResult = mDownloadPreplannedOfflineMapJob?.getResult()
              if (mDownloadPreplannedOfflineMapJob != null && !downloadPreplannedOfflineMapResult.hasErrors()) {
                // get the offline map
                downloadPreplannedOfflineMapResult?.offlineMap?.let { offlineMap ->
                  // add it to the map view
                  mapView.map = offlineMap
                  // add the map name to the list view of downloaded map areas
                  mDownloadedMapAreaNames?.add(offlineMap.item.title)
                  // select the downloaded map area
                  mDownloadedMapAreasListView?.setItemChecked(
                    mDownloadedMapAreaNames?.size - 1,
                    true
                  )
                  mDownloadedMapAreasAdapter?.notifyDataSetChanged()
                  // de-select the area in the preplanned areas list view
                  mPreplannedAreasListView?.clearChoices()
                  mPreplannedMapAreasAdapter?.notifyDataSetChanged()
                  // add the offline map to a list of downloaded map areas
                  mDownloadedMapAreas.add(offlineMap)
                  // hide the area of interest graphics
                  areasOfInterestGraphicsOverlay.isVisible = false
                  // disable the download button
                  downloadButton.isEnabled = false
                }
              } else {
                // collect the layer and table errors into a single alert message
                val stringBuilder = StringBuilder("Errors: ")
                downloadPreplannedOfflineMapResult?.layerErrors?.forEach { (key, value) ->
                  stringBuilder.append("Layer: ").append(key.name).append(". Exception: ")
                    .append(value.message).append(". ")
                }
                downloadPreplannedOfflineMapResult?.tableErrors?.forEach { (key, value) ->
                  stringBuilder.append("Table: ").append(key.tableName).append(". Exception: ")
                    .append(value.message).append(". ")
                }
                val error =
                  "One or more errors occurred with the Offline Map Result: $stringBuilder"
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                Log.e(TAG, error)
              }
            } else {
              val error = "Job finished with an error: " + mDownloadPreplannedOfflineMapJob?.error
              Toast.makeText(this, error, Toast.LENGTH_LONG).show()
              Log.e(TAG, error)
            }
          }
        } catch (e: InterruptedException) {
          val error =
            "Failed to generate default parameters for the download job: " + e.cause!!.message
          Toast.makeText(this, error, Toast.LENGTH_LONG).show()
          Log.e(
            TAG,
            error
          )
        } catch (e: ExecutionException) {
          val error =
            "Failed to generate default parameters for the download job: " + e.cause!!.message
          Toast.makeText(this, error, Toast.LENGTH_LONG).show()
          Log.e(
            TAG,
            error
          )
        }
      }
    }
  }

  private fun createPreplannedAreasListView(
    onlineMap: ArcGISMap,
    offlineMapTask: OfflineMapTask
  ) {
    // create a list view which holds available preplanned map areas
    mPreplannedAreasListView =
      findViewById(R.id.availablePreplannedAreasListView)
    var preplannedMapAreas: List<PreplannedMapArea>
    val preplannedMapAreaNames: MutableList<String> = ArrayList()
    mPreplannedMapAreasAdapter = ArrayAdapter(this, R.layout.item_map_area, preplannedMapAreaNames)
    mPreplannedAreasListView?.adapter = mPreplannedMapAreasAdapter
    // get the preplanned map areas from the offline map task and show them in the list view
    val preplannedMapAreasFuture =
      offlineMapTask.preplannedMapAreasAsync
    preplannedMapAreasFuture.addDoneListener {
      try {
        // get the preplanned areas and add them to the list view
        preplannedMapAreas = preplannedMapAreasFuture.get()
        preplannedMapAreas.forEach { preplannedMapArea ->
          preplannedMapAreaNames.add(preplannedMapArea.portalItem.title)
        }
        mPreplannedMapAreasAdapter?.notifyDataSetChanged()
        // load each area and show a red border around their area of interest
        preplannedMapAreas.forEach { preplannedMapArea ->
          preplannedMapArea.loadAsync()
          preplannedMapArea.addDoneLoadingListener {
            if (preplannedMapArea.loadStatus == LoadStatus.LOADED) {
              areasOfInterestGraphicsOverlay?.graphics?.add(Graphic(preplannedMapArea.areaOfInterest))
            } else {
              val error =
                "Failed to load preplanned map area: " + preplannedMapArea.loadError.message
              Toast.makeText(this, error, Toast.LENGTH_LONG).show()
              Log.e(TAG, error)
            }
          }
        }
        // on list view click
        mPreplannedAreasListView?.onItemClickListener =
          AdapterView.OnItemClickListener { _: AdapterView<*>?, _: View?, i: Int, l: Long ->
            mSelectedPreplannedMapArea = preplannedMapAreas[i]
            if (mSelectedPreplannedMapArea != null) {
              // show graphics overlay which highlights available preplanned map areas
              areasOfInterestGraphicsOverlay.isVisible = true
              // clear the download jobs list view selection
              mDownloadedMapAreasListView?.clearChoices()
              mDownloadedMapAreasAdapter?.notifyDataSetChanged()
              // show the online map with the areas of interest
              mapView.map = onlineMap
              areasOfInterestGraphicsOverlay.isVisible = true
              // set the viewpoint to the preplanned map area's area of interest
              val areaOfInterest =
                GeometryEngine.buffer(mSelectedPreplannedMapArea!!.areaOfInterest, 50.0).extent
              mapView.setViewpointAsync(Viewpoint(areaOfInterest), 1.5f)
              // enable download button only for those map areas which have not been downloaded already
              val mapFile =
                File(externalCacheDir?.path + getString(R.string.preplanned_offline_map_dir) + File.separator + mSelectedPreplannedMapArea?.portalItem?.title)
              downloadButton.isEnabled = !mapFile.exists()
            } else {
              downloadButton.isEnabled = false
            }
          }
      } catch (e: InterruptedException) {
        val error = "Failed to get the Preplanned Map Areas from the Offline Map Task."
        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        Log.e(TAG, error)
      } catch (e: ExecutionException) {
        val error = "Failed to get the Preplanned Map Areas from the Offline Map Task."
        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        Log.e(TAG, error)
      }
    }
  }

  private fun createDownloadAreasListView() {
    // create a list view which holds downloaded map areas
    mDownloadedMapAreaNames = ArrayList()
    mDownloadedMapAreasAdapter = ArrayAdapter(this, R.layout.item_map_area, mDownloadedMapAreaNames)
    downloadedMapAreasListView.apply {
      adapter = mDownloadedMapAreasAdapter
      onItemClickListener =
        AdapterView.OnItemClickListener { _: AdapterView<*>?, _: View?, i: Int, _: Long ->
          // set the downloaded map to the map view
          mapView.map = mDownloadedMapAreas[i]
          // disable the download button
          downloadButton.isEnabled = false
          // clear the available map areas list view selection
          mPreplannedAreasListView?.clearChoices()
          mPreplannedMapAreasAdapter?.notifyDataSetChanged()

          // hide the graphics overlays
          areasOfInterestGraphicsOverlay.isVisible = false
        }
    }
  }

  /**
   * Dismiss the dialog.
   */
  private fun dismissDialog() {
    // dismiss progress dialog
    if (findProgressDialogFragment() != null) {
      findProgressDialogFragment()!!.dismiss()
    }
  }

  /**
   * Show dialog and track progress.
   */
  private fun showProgressDialog() {
    // show progress of the download preplanned offline map job in a dialog
    if (findProgressDialogFragment() == null) {
      val progressDialogFragment = ProgressDialogFragment
        .newInstance(
          "Download preplanned offline map job", "Downloading the requested preplanned map area...",
          "Cancel"
        )
      progressDialogFragment.show(
        supportFragmentManager,
        ProgressDialogFragment::class.java.simpleName
      )

      // track progress
      mDownloadPreplannedOfflineMapJob?.addProgressChangedListener {
        findProgressDialogFragment()?.setProgress(mDownloadPreplannedOfflineMapJob!!.progress)
      }
    }
  }

  /**
   * Find and return the progress dialog fragment.
   *
   * @return the progress dialog fragment.
   */
  private fun findProgressDialogFragment(): ProgressDialogFragment? {
    return supportFragmentManager
      .findFragmentByTag(ProgressDialogFragment::class.java.simpleName) as ProgressDialogFragment?
  }

  /**
   * Callback to cancel the download preplanned offline map job on progress dialog cancel button click.
   */
  override fun onProgressDialogDismiss() {
    mDownloadPreplannedOfflineMapJob?.cancel()
  }

  override fun onPause() {
    mapView.pause()
    super.onPause()
  }

  override fun onResume() {
    super.onResume()
    mapView.resume()
  }

  override fun onDestroy() {
    mapView.dispose()
    super.onDestroy()
  }

  companion object {
    private val TAG =
      MainActivity::class.java.simpleName
  }
}