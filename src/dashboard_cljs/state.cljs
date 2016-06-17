(ns dashboard-cljs.state
  (:require [reagent.core :as r]))

;; Global state is stored here. This namespace should NOT depend on any other
;; namespace in dashboard-cljs. It is solely for storing r/atoms that are
;; needed globally.

(def landing-state (r/atom {:tab-content-toggle {}
                            :nav-bar-collapse true}))

(def default-user {:editing? false
                   :retrieving? false
                   :referral_comment ""
                   :errors nil})

(def users-state (r/atom {:confirming? false
                          :confirming-edit? false
                          :current-user nil
                          :edit-user default-user
                          :alert-success ""
                          :view-log? false
                          :users-count 0
                          :members-count 0
                          :tab-content-toggle {}
                          :user-orders-current-page 1
                          :search-retrieving? false
                          :search-results #{}
                          :recent-search-term ""
                          :search-term ""
                          :cross-link-retrieving? false
                          :user-orders-retrieving? false
                          :update-members-count-saving? false}))
