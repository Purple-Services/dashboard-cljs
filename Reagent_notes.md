# Component-level state

see: https://github.com/reagent-project/reagent-cookbook/tree/master/basics/component-level-state

In order to update a component with local state, the component must be a fn that
returns hiccup.

ex:

(defn modify-row [row]
(let [state (r/atom {:editing false})]
    (fn []
      (if (:editing @state)
        [:input {:id "save-row"
                 :type "submit"
                 :value "Save Changes"
                 :props @state
                 :on-click
                 #(reset! state {:editing false})}]
        [:a {:id "edit-row" :class "btn btn-default edit-icon"
             :props @state
             :on-click
             #(do
               (reset! state {:editing true}))}
         [:i {:class "fa fa-pencil"}]]))))

## Component props

(defn courier-row [courier] 
  (let [row-state (r/atom {:editing? false
                           :zones (:zones courier)}) ;; in this case, courier is the intial prop value when the component is first created. The value of courier does NOT change, even when subsequent renders are occur that would cause it to be updated
        
        ]
		(fn [courier] ;; you must pass courier as a prop here if you want to access its value as it is updated
        ;; on subsequent render calls. It might change, for example, when a new value of courier
		;; is passed to this component
      (when (not (:editing? @row-state))
        (swap! row-state assoc :zones (:zones courier)))
      [:tr
       (if (:connected courier)
         [:td {:class "currently-connected connected"} "Yes"]
         [:td {:class "currently-not-connected connected"} "No"])
       [:td (:name courier)]
       [:td (:phone_number courier)]
       [:td (if (:busy courier) "Yes" "No")]
       [:td (unix-epoch->hrf (:last_ping courier))]
       [:td (:lateness courier)]
       [:td ;;(:zones courier)
        [editable-input row-state :zones]
        ]
       [:td [:button
             {:on-click
              (fn []
                (when (:editing? @row-state)
                  ;; do something to update the courier
                  )
                (swap! row-state update-in [:editing?] not))}
             (if (:editing? @row-state) "Save" "Edit")]]])))

## core.async go-loop

The rendering fn of a component is called many times. Therefore, if a go-loop
is included inside of this fn, it will be re-created each time the component
is rendered. The consequence of this is explored below.

Consider the following component snippet:

```clojure
(defn user-row [table-state user]
  (let [row-state (r/atom {:checked? false})
        messages (sub notify-chan (:id user) (chan))
        this     (r/current-component)]
    (go-loop [m (<! messages)]
      (swap! row-state assoc :checked? false)
      (.log js/log (clj->js m))
      (recur (<! messages)))
    (fn [table-state user] ...)))
```

The body of the let statement contains both the render function
and the go-loop for the component. A message that is sent to notify-chan
on the (:id user) topic will only be logged to the console once.

However, in this code:

```clojure
(defn user-row [table-state user]
  (let [row-state (r/atom {:checked? false})]
    (fn [table-state user]
      (let [messages (sub notify-chan (:id user) (chan))
            this     (r/current-component)]
        (go-loop [m (<! messages)]
          (swap! row-state assoc :checked? false)
          (.log js/log (clj->js m))
          (recur (<! messages))))
      ...)))
```

The go-loop is within the rendering function itself. Each time the component
is rendered, another go-loop will be called. Thus, a message sent to notify-chan
on the (:id user) topic will be logged to the console as many times as the
component has been rendered! Over the lifetime of the app instance, this can
result in many, many messages being sent (erroneously!).


# Using prexisting React component

Components can be used from other libraries. Here is an example using the
react bootstrap library provided through cljsjs.

Include library in the dependencies of project.clj:
```clojure
[cljsjs/react-bootstrap "0.27.3-0"
                  :exclusions [org.webjars.bower/jquery
                  cljsjs/react-dom]]
```
## Caveat
The exlcusions are due to the fact that cljsjs/react-dom wants to use an older
versions of react and reagent is providing a newer versions.

require react-bootstrap
```clojure
(:require [cljsjs.react-bootstrap])
```
This process would be more involved if the library was a foreign lib.

The Tab navigation component will be used. First, define the Tabs
and Tab component from the library

```clojure
(def Tabs (r/adapt-react-class js/ReactBootstrap.Tabs))
(def Tab (r/adapt-react-class js/ReactBootstrap.Tab))
```

Now create a component using these sub-components:

```clojure
(defn tab-nav-comp []
  (fn []
    [Tabs {:position "left"
           :standalone true
           :tabWidth 3
           }
     [Tab {:eventKey "1"
           :title "Users"
           :tabClassName "custom-tab"} [users-component]]
     [Tab {:eventKey "2"
           :title "Orders"} [orders-component]]
     [Tab {:eventKey "3"
           :title "Tab 3"} "Tab 3 content"]]))
```

The props MUST be defined (the map {} given as the first argument). This
will not work

```clojure
[Tab [users-component]]
```
