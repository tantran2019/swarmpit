(ns swarmpit.component.service.create-image
  (:require [material.icon :as icon]
            [material.component.list.basic :as list]
            [material.components :as comp]
            [swarmpit.component.mixin :as mixin]
            [swarmpit.component.state :as state]
            [swarmpit.component.common :as common]
            [swarmpit.component.progress :as progress]
            [swarmpit.component.message :as message]
            [swarmpit.ajax :as ajax]
            [swarmpit.routes :as routes]
            [swarmpit.docker.utils :as du]
            [swarmpit.utils :refer [shortint]]
            [swarmpit.url :refer [dispatch!]]
            [sablono.core :refer-macros [html]]
            [clojure.string :as str]
            [rum.core :as rum]))

(enable-console-print!)

(defn- filter-items
  [items predicate]
  (filter #(str/includes? (:name %) predicate) items))

(defn- registries-handler
  []
  (ajax/get
    (routes/path-for-backend :registries)
    {:state      [:loading? :registry]
     :on-success (fn [{:keys [response]}]
                   (doseq [item response]
                     (state/add-item item (conj state/form-state-cursor :registries))))}))

(defn- dockerhub-handler
  []
  (ajax/get
    (routes/path-for-backend :dockerhub-users)
    {:state      [:loading? :dockerhub]
     :on-success (fn [{:keys [response]}]
                   (doseq [item response]
                     (state/add-item item (conj state/form-state-cursor :registries))))}))

(defn- public-repository-handler
  [query page]
  (ajax/get
    (routes/path-for-backend :public-repositories)
    {:params     {:query query
                  :page  page}
     :state      [:searching?]
     :on-success (fn [{:keys [response]}]
                   (state/set-value (:results response) state/form-value-cursor))
     :on-error   (fn [{:keys [response]}]
                   (message/error
                     (str "Repositories fetching failed. " (:error response))))}))

(defn- repository-handler
  [registry-id registry-route]
  (ajax/get
    (routes/path-for-backend registry-route {:id registry-id})
    {:state      [:searching?]
     :on-success (fn [{:keys [response]}]
                   (state/set-value response state/form-value-cursor))
     :on-error   (fn [{:keys [response]}]
                   (message/error
                     (str "Repositories fetching failed. " (:error response))))}))

(defn- onclick-handler
  [repository]
  (dispatch!
    (routes/path-for-frontend :service-create-config
                              {}
                              {:repository repository})))

(defn- init-form-state
  []
  (state/set-value {:loading?   {:registry  true
                                 :dockerhub true}
                    :manual     false
                    :searching? false
                    :registries []
                    :registry   nil
                    :repository ""
                    :active     "public"} state/form-state-cursor))

(defn- init-form-value
  []
  (state/set-value [] state/form-value-cursor))

(def mixin-init-form
  (mixin/init-form
    (fn [_]
      (init-form-state)
      (init-form-value)
      (registries-handler)
      (dockerhub-handler))))

(defn- form-manual [value]
  (comp/switch
    {:name     "manual"
     :key      "manual"
     :color    "primary"
     :value    (str value)
     :checked  value
     :onChange #(state/update-value [:manual] (-> % .-target .-checked) state/form-state-cursor)}))

(defn- repository-adornmennt [repository]
  (comp/input-adornment
    {:position "end"}
    (comp/icon-button
      {:onClick     #(dispatch!
                       (routes/path-for-frontend
                         :service-create-config
                         {}
                         {:repository repository}))
       :onMouseDown (fn [event]
                      (.preventDefault event))}
      (icon/arrow-forward {}))))

(rum/defc form-repository-manual < rum/static [repository]
  (comp/text-field
    {:fullWidth       true
     :id              "repository"
     :value           repository
     :margin          "dense"
     :variant         "outlined"
     :placeholder     "Enter valid repository"
     :style           {:maxWidth "400px"}
     :InputLabelProps {:shrink true}
     :InputProps      {:className    "Swarmpit-form-input"
                       :endAdornment (repository-adornmennt repository)}
     :onChange        (fn [event]
                        (state/update-value [:repository] (-> event .-target .-value) state/form-state-cursor))}))

(defn public-reg []
  (comp/menu-item
    {:key   "public"
     :value "public"}
    (comp/list-item-icon
      {}
      (icon/group {}))
    (comp/list-item-text
      {:primary   "public"
       :className "Swarmpit-repo-registry-item"})))

(defn dockerhub-reg [account index]
  (comp/menu-item
    {:key   (:username account)
     :value index}
    (comp/list-item-icon
      {}
      (comp/svg icon/docker-path))
    (comp/list-item-text
      {:primary   (:username account)
       :className "Swarmpit-repo-registry-item"})))

(defn other-reg [account index]
  (comp/menu-item
    {:key   (:name account)
     :value index}
    (comp/list-item-icon
      {}
      (comp/svg icon/registries-path))
    (comp/list-item-text
      {:primary   (html
                    [:span.Swarmpit-repo-registry-text
                     [:span (:name account)]
                     [:span.grow]
                     [:span.Swarmpit-repo-registry-url (:url account)]])
       :className "Swarmpit-repo-registry-item"})))

(defn- on-change-registry [event registries]
  (let [value (-> event .-target .-value)]
    (state/set-value [] state/form-value-cursor)
    (state/update-value [:repository] "" state/form-state-cursor)
    (state/update-value [:active] value state/form-state-cursor)
    (when (not= "public" value)
      (let [registry (nth registries value)]
        (state/update-value [:registry] registry state/form-state-cursor)
        (repository-handler
          (:_id registry)
          (case (:type registry)
            "dockeruser" :dockerhub-repositories
            "registry" :registry-repositories))))))

(rum/defc form-registry < rum/static [registries active searching?]
  (comp/text-field
    {:select          true
     :fullWidth       true
     :label           "Registry"
     :value           active
     :helperText      "Repository source"
     :variant         "outlined"
     :margin          "dense"
     :disabled        searching?
     :style           {:maxWidth "400px"}
     :InputLabelProps {:shrink true}
     :InputProps      {:className "Swarmpit-form-input Swarmpit-form-select-icon"}
     :onChange        (fn [event]
                        (on-change-registry event registries))}
    (public-reg)
    (->> registries
         (map-indexed
           (fn [index i]
             (if (= "dockeruser" (:type i))
               (dockerhub-reg i index)
               (other-reg i index)))))))

(defn- on-change-search [event active]
  (let [value (-> event .-target .-value)]
    (state/update-value [:repository] value state/form-state-cursor)
    (when (= "public" active)
      (public-repository-handler value 1))))

(rum/defc form-search < rum/static [repository active]
  (comp/text-field
    {:fullWidth       true
     :id              "repository"
     :value           repository
     :margin          "dense"
     :variant         "outlined"
     :placeholder     "Find repository"
     :style           {:maxWidth "400px"}
     :InputLabelProps {:shrink true}
     :InputProps      {:className "Swarmpit-form-input"
                       :startAdornment
                                  (comp/input-adornment
                                    {:position "start"} (icon/search {}))}
     :onChange        (fn [event]
                        (on-change-search event active))}))

(def render-list-metadata
  {:primary   (fn [{:keys [name]}] name)
   :status-fn (fn [{:keys [official private]}]
                [(when official
                   (comp/tooltip
                     {:key   "official"
                      :title "Official Image"} (icon/verified {})))
                 (when private
                   (comp/tooltip
                     {:key   "private"
                      :title "Private"} (icon/lock {})))])
   :secondary (fn [{:keys [stars pulls description]}]
                (html [(when (< 0 stars)
                         [:span (shortint stars) " stars" [:span.Service-repo-separator " • "]])
                       (when (< 0 pulls)
                         [:span (shortint pulls) " pulls"])
                       (when (not (empty? description))
                         [:span
                          (comp/hidden
                            {:key  "break"
                             :mdUp true} (html [:br]))
                          (comp/hidden
                            {:key    "separator"
                             :smDown true} (html [:span.Service-repo-separator " • "]))
                          description])]))})

(rum/defc form-repo < rum/reactive []
  (let [{:keys [repository manual searching? registries registry active]} (state/react state/form-state-cursor)
        repositories (state/react state/form-value-cursor)
        filtered-repositories (filter-items repositories repository)]
    (comp/mui
      (html
        [:div.Swarmpit-form
         [:div.Swarmpit-form-context
          [:div.Swarmpit-form-paper
           (common/edit-title "Select a repository" "define image for new service")
           [:div.Swarmpit-repo-manual
            (comp/form-control
              {:component "fieldset"}
              (comp/form-group
                {}
                (comp/form-control-label
                  {:control (form-manual manual)
                   :label   (comp/typography
                              {:className "Swarmpit-repo-manual-label"}
                              "Specify repository manually")})))]
           (if manual
             (form-repository-manual repository)
             [:div
              [:div (form-registry registries active searching?)]
              [:div (form-search repository active)]
              (when searching?
                (comp/linear-progress {:className "Swarmpit-repo-progress"}))
              (cond
                searching? [:span]
                (and
                  (not (str/blank? repository))
                  (empty? filtered-repositories)) (comp/typography
                                                    {:className "Swarmpit-repo-state"} "Nothing matches this filter.")
                (empty? repositories) [:span]
                :else
                (comp/card
                  {:className "Swarmpit-form-card Swarmpit-repo-card"}
                  (comp/card-content
                    {:className "Swarmpit-table-card-content"}
                    (comp/list
                      {:dense true}
                      (map-indexed
                        (fn [index item]
                          (list/list-item
                            render-list-metadata
                            index
                            item
                            (last filtered-repositories)
                            #(onclick-handler
                               (if (= "registry" (:type registry))
                                 (du/repository (:url registry) (:name item))
                                 (:name item))))) filtered-repositories)))))])]]]))))

(rum/defc form < rum/reactive
                 mixin-init-form [_]
  (let [{:keys [loading?]} (state/react state/form-state-cursor)]
    (progress/form
      (or (:registry loading?)
          (:dcokerhub loading?))
      (form-repo))))