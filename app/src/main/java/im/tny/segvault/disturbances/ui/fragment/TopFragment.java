package im.tny.segvault.disturbances.ui.fragment;

import android.content.Context;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.view.Gravity;
import android.view.ViewGroup;

import im.tny.segvault.disturbances.MainService;
import im.tny.segvault.disturbances.R;
import im.tny.segvault.disturbances.ui.util.CustomFAB;

/**
 * Created by gabriel on 5/6/17.
 */

public abstract class TopFragment extends Fragment implements MainAddableFragment {
    private OnInteractionListener mListener;

    protected void setUpActivity(String title, boolean withFab, boolean withRefresh) {
        getActivity().setTitle(title);
        if (mListener != null) {
            mListener.checkNavigationDrawerItem(getNavDrawerId());
        }
        CustomFAB fab = getActivity().findViewById(R.id.fab);
        if (withFab) {
            fab.show();

            CoordinatorLayout.LayoutParams params = new CoordinatorLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            params.setMargins(getResources().getDimensionPixelOffset(R.dimen.fab_margin),
                    getResources().getDimensionPixelOffset(R.dimen.fab_margin),
                    getResources().getDimensionPixelOffset(R.dimen.fab_margin),
                    getResources().getDimensionPixelOffset(R.dimen.fab_margin));
            fab.setLayoutParams(params);
        } else {
            fab.hide();
        }
        fab.setOnClickListener(null);

        SwipeRefreshLayout srl = getActivity().findViewById(R.id.swipe_container);
        srl.setEnabled(withRefresh);
        srl.setRefreshing(false);
        srl.setOnRefreshListener(null);
    }

    protected CustomFAB getFloatingActionButton() {
        return (CustomFAB) getActivity().findViewById(R.id.fab);
    }

    protected SwipeRefreshLayout getSwipeRefreshLayout() {
        return (SwipeRefreshLayout) getActivity().findViewById(R.id.swipe_container);
    }

    protected void switchToPage(String pageString) {
        if (mListener != null) {
            mListener.switchToPage(pageString, true);
        }
    }

    public boolean isScrollable() {
        return true;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof OnInteractionListener) {
            mListener = (OnInteractionListener) context;
        } else {
            throw new RuntimeException(context.toString()
                    + " must implement TopFragment.OnInteractionListener");
        }
    }

    public interface OnInteractionListener {
        MainService getMainService();

        void checkNavigationDrawerItem(int id);

        void switchToPage(String pageString, boolean addToBackStack);
    }
}
