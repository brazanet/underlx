package im.tny.segvault.subway;

/**
 * Created by gabriel on 4/27/17.
 */

public interface IEdgeWeighter {
    double getEdgeWeight(Network network, Connection connection);
    void setRouteSource(Stop routeSource);
    void setRouteTarget(Stop routeTarget);
}
