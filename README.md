# dashboard-cljs

ClojureScript for the internal Purple Dashboard

## Javascript and ClojureScript

ClojureScript uses the Google closure compiler to translate ClojureScript to Javascript. There is a basic and advanced compilation mode. The basic compilation outputs Javascript that is suitable for debugging purposes. The advanced compilation mode produces a single file of minified Javascript with dead code pruned and is suitable for a production release.

There can be discrepancies in the way the code is compiled between the two modes. One common problem is the way javascript object properties are accessed in clojurescript. For example, in javascript the URL property of document is accessed via:

```javascript
document.URL
```

In clojurescript, this can be accessed in two ways.

```clojurescript
;; first example
(.-URL document)
;; second example
(aget document "URL")
```

The above two statements are equivalent when using basic compilation. However, in advanced compilation mode the first example will fail due to name mangling. The reason that the second example works and the first one doesn't is that the Google closure compiler in advanced compilation mode (which is used by the clojurescript compiler) will not touch strings.

Development of the Purple dash is full-stack development work on the dashboard-service and dashboard-cljs repositories. The following outline provides a basic workflow that assumes a directory structure that matches that of the Purple-Services repository.

For example:

```
Purple-Services
	|
	|- dashboard-service
	|
	|- dashboard-cljs
```

# Development Workflow

0. Produce a new route or feature in dashboard-service (optional)
1. Develop the interface using figwheel
2. Test the advanced compilation output with dashboard-service

## 1. Develop the interface using figwheel

You will need two terminals open in order to compile and run the code using this workflow.

*Advanced compilation*
```bash
$ lein cljsbuild auto release
```

The dashboard-service server should be running when developing. Start it in the dashboard-service dir:

```bash
$ lein ring server
```

The advanced compilation target is
```
../dashboard-service/src/public/js/dashboard_cljs.js
```

*Figwheel*
```bash
$ rlwrap lein figwheel
```

Note: rlwrap provides a readline wrapper for the figwheel REPL

Connect the browser to the figwheel server by browsing to http://localhost:3449/index.html. After it connects, the figwheel REPL will become active in the terminal. When changes are made to the codebase, Figwheel will automatically update the browser code. If there are errors during compilation, Figwheel will report them.

Figwheel serves index.html from the resources/public dir. The code depends on setting a base-url in the html file that it is served from. For example, index.html and index_dashmap.html both have this div:

```html
<div id="base-url" value="http://localhost:3001/dashboard/" style="display: none;"></div>
```

where the attribute 'value' is the base-url. The clojurescript code pulls the value attribute from div#base-url in order to set the base-url used in server calls. The base-url defined in the div above assumes the server is running on the default port of 3001 (defined in dashboard-services/profiles.clj).

If you would like to open up a separate Chrome dev environment for development, use the follwing command:

```bash
$  /Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --disable-web-security --user-data-dir=/tmp/chrome2/ \ http://localhost:3449/index.html
```

Alternatively, create a script named 'chrome-file-open':

```bash
#!/bin/bash

/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --disable-web-security --user-data-dir=/tmp/chrome2/ \ $1
```

chmod +x this script and put it on your $PATH. You can then open the files with:

```bash
$ chrome-file-open http://localhost:3449/index.html
```

### figwheel notes

Most of the dashboard makes use of the Reagent ClojureScript React wrapper. The real-time map is written in ClojureScript, but does not make use of Reagent. The code for the map resides in gmaps.cljs. 

The rest of the notes pertain to development of the main dashboard excluding gmaps.cljs.

#### You can check the datastore atoms, which contains all of the data downloaded from the server, in the figwheel REPL

Ex:
```clojure
cljs.user=> (first @dashboard-cljs.datastore/orders)
{:address_state "", :tire_pressure_check false, :was-late false, :email "jatkins10@gmail.com", :customer_phone_number "8587404160", :target_time_start 1481648369, :payment_info "{\"id\":\"card_18V5D9D0DZN191iCL9EEh9Ks\",\"brand\":\"Discover\",\"exp_month\":6,\"exp_year\":2021,\"last4\":\"5376\"}", :courier_id "8qhjmZ040BhVcPR81i2r", :vehicle_id "SjW1qG3ECRoVRW3TCxW4", :total_price 3589, :stripe_charge_id "ch_19QJcjD0DZN191iCcb7rtqvl", :coupon_code "", :target_time_end 1481666369, :special_instructions "", :event_log "assigned 1481648470|accepted 1481648486|enroute 1481648490|servicing 1481655128|complete 1481655562|", :subscription_id 0, :number_rating nil, :courier_name "Steven Brooks", :status "complete", :id "D1PeEYCgVKabU7EB9Lcy", :notes "", :zones [1 3 359 325], :paid true, :lat 33.0035751221569, :address_zip "92075", :gallons 10, :user_id "P3EKmVDSjz5Nm73yCLZX", :vehicle {:id "SjW1qG3ECRoVRW3TCxW4", :year "2012", :make "Acura", :model "RDX", :color "Gray", :gas_type "91", :license_plate "6UWF118"}, :admin_event_log nil, :market "SD", :first_order? false, :market-color "#4DAF7C", :submarket "Encinitas", :address_street "780 Santa Victoria", :text_rating "", :license_plate "6UWF118", :customer_name "Jesse atkins", :address_city "", :lng -117.251064794872, :timestamp_created "2016-12-13T10:59:30Z", :gas_type "91"}
cljs.user=>
```

These are the atoms for each dataset used by the dashboard:

```
dashboard-cljs.datastore/orders
dashboard-cljs.datastore/couriers
dashboard-cljs.datastore/users
dashboard-cljs.datastore/coupons
dashboard-cljs.datastore/zones
```

#### on-jsload

When developing a feature for a particular tab on the dashboard (Home, Orders, Coupons, etc.), it is convinient to have figwheel go to that tab on every reload. In dev.cljs (not included in the advanced release compilation), you can change the view that is loaded. For example, to have zones tab selected, use:

```clojure
(defn ^:export on-jsload
  []
  (core/init-new-dash)
  (utils/select-toggle-key! (r/cursor state/landing-state [:tab-content-toggle])
                            :zones-view))
```			   

## Adapating native React libraries for use with Reagent

Example:  https://github.com/fmoo/react-typeahead

1. Add library to cljsbuild. The react-typeahead.js was found in the dist dir of
the github project.

```clojure
:foreign-libs [;; https://github.com/googlemaps/js-map-label
               {:file "resources/js/maplabel.js"
                :provides ["maplabel"]}
               ;; https://github.com/fmoo/react-typeahead
               {:file "resources/js/react-typeahead.js"
                :provides ["react-typeahead"]}]
```

2. add [react-typeahead] to require statement of namespace it will be used in

3. Create a fn to access it
```clojure
(defn typeahead []
  js/ReactTypeahead.Typeahead.)
```

Note: It is not always immediately clear what the class name is by simply
looking at the javascript. Might have to explore with in the javascript console
as in the above case.

4. adapt the class

```clojure
(def type-ahead (r/adapt-react-class (typeahead)))
```

5. use the class
```clojure
[type-ahead {:options ["Los Angeles" "Orange County"
                       "Seattle" "San Diego"]
             :maxVisible 2}]
```
## License


Copyright © 2017 Purple Services Inc
