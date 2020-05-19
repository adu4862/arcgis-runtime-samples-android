package com.esri.arcgisruntime.sample.offlinegeocode

import android.database.MatrixCursor
import android.graphics.Color
import android.os.Bundle
import android.provider.BaseColumns
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.cursoradapter.widget.SimpleCursorAdapter
import com.esri.arcgisruntime.concurrent.ListenableFuture
import com.esri.arcgisruntime.data.TileCache
import com.esri.arcgisruntime.geometry.Point
import com.esri.arcgisruntime.layers.ArcGISTiledLayer
import com.esri.arcgisruntime.loadable.LoadStatus
import com.esri.arcgisruntime.mapping.ArcGISMap
import com.esri.arcgisruntime.mapping.Basemap
import com.esri.arcgisruntime.mapping.Viewpoint
import com.esri.arcgisruntime.mapping.view.DefaultMapViewOnTouchListener
import com.esri.arcgisruntime.mapping.view.Graphic
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay
import com.esri.arcgisruntime.symbology.SimpleMarkerSymbol
import com.esri.arcgisruntime.tasks.geocode.GeocodeParameters
import com.esri.arcgisruntime.tasks.geocode.GeocodeResult
import com.esri.arcgisruntime.tasks.geocode.LocatorTask
import com.esri.arcgisruntime.tasks.geocode.ReverseGeocodeParameters
import kotlinx.android.synthetic.main.activity_main.*
import kotlin.math.roundToInt


class MainActivity : AppCompatActivity() {

  private val TAG = MainActivity::class.java.simpleName
  private val geocodeParameters = GeocodeParameters().apply {
    resultAttributeNames.add("*")
    maxResults = 1
  }

  private val reverseGeocodeParameters: ReverseGeocodeParameters by lazy {
    ReverseGeocodeParameters().apply {
      resultAttributeNames.add("*")
      outputSpatialReference = mapView.map.spatialReference
      maxResults = 1
    }
  }

  private val locatorTask: LocatorTask by lazy {
    LocatorTask(
      getExternalFilesDir(null)?.path + resources.getString(R.string.san_diego_loc)
    )
  }

  // create a point symbol for showing the address location
  private val pointSymbol = SimpleMarkerSymbol(SimpleMarkerSymbol.Style.CIRCLE, Color.RED, 20.0f)


  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    // load the tile cache from local storage
    val tileCache =
      TileCache(getExternalFilesDir(null)?.path + getString(R.string.san_diego_tpk))
    // use the tile cache extent to set the view point
    tileCache.addDoneLoadingListener { mapView.setViewpoint(Viewpoint(tileCache.fullExtent)) }
    // create a tiled layer and add it to as the base map
    val tiledLayer = ArcGISTiledLayer(tileCache)
    // set the map to the map view
    mapView.map = ArcGISMap().apply { basemap = Basemap(tiledLayer) }
    // add a graphics overlay to the map view
    mapView.graphicsOverlays.add(GraphicsOverlay())
    // add a touch listener to the map view
    mapView.onTouchListener = object : DefaultMapViewOnTouchListener(this, mapView) {
      override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        val screenPoint = android.graphics.Point(e.x.roundToInt(), e.y.toInt())
        reverseGeocode(mapView.screenToLocation(screenPoint))
        return true
      }

      override fun onDoubleTouchDrag(e: MotionEvent): Boolean {
        return onSingleTapConfirmed(e)
      }
    }


    // load the locator task from external storage
    locatorTask.loadAsync()
    locatorTask.addDoneLoadingListener { setupAddressSearchView() }
  }

  /**
   * Sets up the address SearchView and uses MatrixCursor to show suggestions to the user
   */
  private fun setupAddressSearchView() {
    // get the list of pre-made suggestions
    val suggestions = resources.getStringArray(R.array.suggestion_items).toList()

    // set up parameters for searching with MatrixCursor
    val columnNames = arrayOf(BaseColumns._ID, "address")
    val suggestionsCursor = MatrixCursor(columnNames)

    // add each address suggestion to a new row
    suggestions.forEachIndexed() { i, s -> suggestionsCursor.addRow(arrayOf(i, s)) }

    // create the adapter for the search view's suggestions
    searchView.apply {
      suggestionsAdapter = SimpleCursorAdapter(
        this@MainActivity,
        R.layout.suggestion,
        suggestionsCursor,
        arrayOf("address"),
        intArrayOf(R.id.suggestion_address),
        0
      )

      // geocode the searched address on submit
      setOnQueryTextListener(object : SearchView.OnQueryTextListener {
        override fun onQueryTextSubmit(address: String): Boolean {
          geoCodeTypedAddress(address)
          searchView.clearFocus()
          return true
        }

        override fun onQueryTextChange(newText: String?) = true
      })

      // geocode a suggestions when selected
      setOnSuggestionListener(object : SearchView.OnSuggestionListener {
        override fun onSuggestionSelect(position: Int) = true

        override fun onSuggestionClick(position: Int): Boolean {
          geoCodeTypedAddress(suggestions[position])
          return true
        }
      })
    }
  }

  /**
   * Use the locator task to geocode the the given address.
   *
   * @param address as a string
   */
  private fun geoCodeTypedAddress(address: String) {
    // Execute async task to find the address
    locatorTask.addDoneLoadingListener {
      if (locatorTask.loadStatus != LoadStatus.LOADED) {
        val error =
          "Error loading locator task: " + locatorTask.loadError.message
        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        Log.e(TAG, error)
        return@addDoneLoadingListener
      }
      // get a list of geocode results for the given address
      val geocodeFuture: ListenableFuture<List<GeocodeResult>> =
        locatorTask.geocodeAsync(address, geocodeParameters)
      geocodeFuture.addDoneListener {
        try {
          // get the geocode results
          val geocodeResults = geocodeFuture.get()
          if (geocodeResults.isEmpty()) {
            Toast.makeText(this, "No location found for: $address", Toast.LENGTH_LONG).show()
            return@addDoneListener
          }
          // get the first result
          val geocodeResult = geocodeResults[0]
          displayGeocodeResult(geocodeResult.displayLocation, geocodeResult.label)

        } catch (e: Exception) {
          val error = "Error getting geocode result: " + e.message
          Toast.makeText(this, error, Toast.LENGTH_LONG).show()
          Log.e(TAG, error)
        }
      }
    }
  }

  /**
   * Uses the locator task to reverse geocode for the given point.
   *
   * @param point on which to perform the reverse geocode
   */
  private fun reverseGeocode(point: Point) {
    val results = locatorTask.reverseGeocodeAsync(point, reverseGeocodeParameters)
    try {
      val geocodeResults = results.get()
      if (geocodeResults.isEmpty()) {
        Toast.makeText(this, "No addresses found at that location!", Toast.LENGTH_LONG).show()
        return
      }
      // get the top result
      val geocode = geocodeResults[0]
      // attributes from a click-based search
      val street = geocode.attributes["Street"].toString()
      val city = geocode.attributes["City"].toString()
      val state = geocode.attributes["State"].toString()
      val zip = geocode.attributes["ZIP"].toString()
      val detail = "$city, $state $zip"
      val address = "$street,$detail"
      displayGeocodeResult(point, address)

    } catch (e: Exception) {
      val error = "Error getting geocode results: " + e.message
      Toast.makeText(this, error, Toast.LENGTH_LONG).show()
      Log.e(TAG, error)
    }
  }


  /**
   * Draw a point and open a callout showing geocode results on map.
   *
   * @param resultPoint geometry to show where the geocode result is
   * @param address     to display in the associated callout
   */
  private fun displayGeocodeResult(
    resultPoint: Point,
    address: CharSequence
  ) {
    // dismiss the callout if showing
    if (mapView.callout.isShowing) {
      mapView.callout.dismiss()
    }
    val graphicsOverlay = mapView.graphicsOverlays[0]
    // remove any previous graphics/search results
    graphicsOverlay.graphics.clear()
    // create graphic object for resulting location and add it to the ographics overlay
    graphicsOverlay.graphics.add(Graphic(resultPoint, pointSymbol))
    // Zoom map to geocode result location
    mapView.setViewpointAsync(Viewpoint(resultPoint, 8000.0), 3f)
    showCallout(resultPoint, address)
  }

  /**
   * Show a callout at the given point with the given text.
   *
   * @param point to define callout location
   * @param text to define callout content
   */
  private fun showCallout(point: Point, text: CharSequence) {
    val calloutTextView = TextView(this).apply {
      this.text = text
    }
    mapView.callout.apply {
      location = point
      content = calloutTextView
    }
    mapView.callout.show()
  }
}