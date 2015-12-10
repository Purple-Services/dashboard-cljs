# Unique Key when using build-all

A key should be supplied to a build-all call, otherwise React reports:

**Each child in an array should have a unique "key" prop. Check the renderComponent call using <undefined>. See http://fb.me/react-warning-keys for more information.**

Use the options map given to build-all to give a key

ex:
```clojure
(om/build-all courier-view (:couriers state)
	          {:init-state state
	           :key :id})
```
			   
# Events from on-click handler

An event handler must return nil or true, otherwise React reports:

**Warning: Returning `false` from an event handler is deprecated and will be ignored in a future release. Instead, manually call e.stopPropagation() or e.preventDefault(), as appropriate.**

Easy solution:

```clojure
(html/html  [:a {:id "edit-courier" :class "btn btn-default edit-icon"
                      :href "#"
                      :on-click
                      #(do
                         (.preventDefault %)
                         (put! (:pub-chan (om/get-shared owner))
                               {:topic (:id courier) :data %})
                         nil
                        )}
                   [:i {:class "fa fa-pencil"}]]))
``	

There is also a Macro do this discussed here:
https://github.com/reagent-project/reagent/wiki/Beware-Event-Handlers-Returning-False

# Cursors

A sub-cursor MUST be a map or a vector.
(see "Create sub-cursors" in https://github.com/omcljs/om/wiki/Cursors)

Gotcha: Using map instead of mapv to populate a cursor will NOT result in a
sub-cursor when you try to access it. map returns a lazy sequence and mapv
returns a vector. ONLY maps and vectors can be sub-cursors!

# Component state

Each component can have its own state that is independent of the cursor that is
passed to it. This state can be set initially with om/IInitState,
retrieved with om/get-state and set with om/set-state!

The component state CAN NOT BE accessed. Not even by cheating by passing a
component to another component and accessing it on its _owner property. If
a component needs to know another components state, it MUST send it via channels.
There is NO other way to access a components state.

